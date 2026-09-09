package io.github.codexjava.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.codexjava.config.CodexConfig;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class MultiAgentManager implements AutoCloseable {
    private final ObjectMapper mapper;
    private final SessionStore sessionStore;
    private final CodexConfig rootConfig;
    private final AgentFactory agentFactory;
    private final Map<AgentPath, AgentNode> agentsByPath = new ConcurrentHashMap<>();
    private final Map<String, AgentNode> agentsById = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Semaphore executionSlots;

    public MultiAgentManager(
            ObjectMapper mapper,
            SessionStore sessionStore,
            CodexConfig rootConfig,
            AgentFactory agentFactory
    ) {
        this.mapper = mapper;
        this.sessionStore = sessionStore;
        this.rootConfig = rootConfig;
        this.agentFactory = agentFactory;
        this.executionSlots = new Semaphore(
                Math.max(0, rootConfig.multiAgent().maxConcurrentThreads() - 1),
                true
        );
    }

    public AgentExecutionContext registerRoot(SessionStore.Session session) {
        AgentNode root = new AgentNode(
                session.id(),
                AgentPath.root(),
                null,
                session,
                rootConfig,
                new AgentStatus.Running()
        );
        AgentNode existing = agentsByPath.putIfAbsent(AgentPath.root(), root);
        AgentNode effective = existing == null ? root : existing;
        agentsById.putIfAbsent(effective.threadId, effective);
        return effective.context(this);
    }

    public SpawnResult spawn(
            AgentExecutionContext sender,
            String taskName,
            String message,
            String forkTurns,
            String model,
            String reasoningEffort,
            String agentType,
            Boolean forkContext
    ) throws IOException {
        if (forkContext != null) {
            throw new IllegalArgumentException(
                    "fork_context is not supported in MultiAgentV2; use fork_turns instead"
            );
        }
        requireMessage(message);
        AgentPath childPath = sender.agentPath().join(taskName);
        if (agentsByPath.containsKey(childPath)) {
            throw new IllegalArgumentException("agent path `" + childPath + "` already exists");
        }

        ForkMode forkMode = ForkMode.parse(forkTurns);
        if (forkMode instanceof ForkMode.All && model != null && !model.isBlank()) {
            throw new IllegalArgumentException(
                    "Full-history forked agents inherit the parent model; omit model, or spawn without a full-history fork."
            );
        }
        reserveExecutionSlot();
        AgentNode child = null;
        try {
            CodexConfig childConfig = childConfig(forkMode, model);
            ArrayNode inherited = forkHistory(sender.session(), sender.toolCallId(), forkMode);
            SessionStore.Session childSession = sessionStore.create(
                    childConfig.workingDirectory(),
                    childConfig.model(),
                    inherited
            );
            child = new AgentNode(
                    childSession.id(),
                    childPath,
                    sender.agentPath(),
                    childSession,
                    childConfig,
                    new AgentStatus.PendingInit()
            );
            if (agentsByPath.putIfAbsent(childPath, child) != null) {
                throw new IllegalArgumentException("agent path `" + childPath + "` already exists");
            }
            agentsById.put(child.threadId, child);
            enqueue(child, InterAgentMessage.newTask(sender.agentPath(), childPath, message));
            submitReservedTurn(child);
        } catch (IOException | RuntimeException error) {
            if (child != null) {
                agentsByPath.remove(childPath, child);
                agentsById.remove(child.threadId, child);
            }
            executionSlots.release();
            throw error;
        }
        return new SpawnResult(childPath.toString(), null);
    }

    public void sendMessage(AgentExecutionContext sender, String target, String message) {
        deliver(sender, target, message, false);
    }

    public void followupTask(AgentExecutionContext sender, String target, String message) {
        AgentNode receiver = resolve(sender, target);
        if (receiver.path.isRoot()) {
            throw new IllegalArgumentException("Follow-up tasks can't target the root agent");
        }
        requireMessage(message);
        synchronized (receiver) {
            Future<?> active = receiver.activeTask.get();
            boolean mustStart = active == null || active.isDone();
            if (mustStart) {
                reserveExecutionSlot();
            }
            enqueue(
                    receiver,
                    InterAgentMessage.newTask(sender.agentPath(), receiver.path, message)
            );
            if (mustStart) {
                try {
                    submitReservedTurn(receiver);
                } catch (RuntimeException error) {
                    executionSlots.release();
                    throw error;
                }
            }
        }
    }

    public AgentStatus interrupt(AgentExecutionContext sender, String target) {
        AgentNode receiver = resolve(sender, target);
        if (receiver.path.isRoot()) {
            throw new IllegalArgumentException("root is not a spawned agent");
        }
        if (receiver.threadId.equals(sender.threadId())) {
            throw new IllegalArgumentException(
                    "an agent cannot interrupt itself; return your result and let the parent interrupt you if needed"
            );
        }
        AgentStatus previous = receiver.status.get();
        Future<?> task = receiver.activeTask.getAndSet(null);
        if (task != null) {
            task.cancel(true);
        }
        if (!previous.isFinal()) {
            receiver.status.set(new AgentStatus.Interrupted());
        }
        signalActivity(receiver);
        return previous;
    }

    public List<ListedAgent> listAgents(AgentExecutionContext sender, String pathPrefix) {
        AgentPath prefix = pathPrefix == null || pathPrefix.isBlank()
                ? null
                : sender.agentPath().resolve(pathPrefix);
        return agentsByPath.values().stream()
                .filter(agent -> prefix == null || agent.path.matchesPrefix(prefix))
                .sorted(Comparator.comparing(agent -> agent.path))
                .map(agent -> new ListedAgent(agent.path.toString(), agent.status.get()))
                .toList();
    }

    public WaitResult waitForActivity(AgentExecutionContext context, Long requestedTimeoutMillis)
            throws InterruptedException {
        CodexConfig.MultiAgentConfig config = rootConfig.multiAgent();
        long timeout = requestedTimeoutMillis == null
                ? config.defaultWaitTimeoutMillis()
                : requestedTimeoutMillis;
        if (timeout > config.maxWaitTimeoutMillis()) {
            throw new IllegalArgumentException(
                    "timeout_ms must be at most " + config.maxWaitTimeoutMillis()
            );
        }
        long effectiveTimeout = Math.max(timeout, config.minWaitTimeoutMillis());
        AgentNode node = requireNode(context.threadId());
        if (!node.mailbox.isEmpty()) {
            return new WaitResult(clampedMessage("Wait completed.", requestedTimeoutMillis, effectiveTimeout), false);
        }

        long observed = node.activityVersion.get();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(effectiveTimeout);
        synchronized (node.activityMonitor) {
            while (observed == node.activityVersion.get() && node.mailbox.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new WaitResult(
                            clampedMessage("Wait timed out.", requestedTimeoutMillis, effectiveTimeout),
                            true
                    );
                }
                TimeUnit.NANOSECONDS.timedWait(node.activityMonitor, remaining);
            }
        }
        return new WaitResult(clampedMessage("Wait completed.", requestedTimeoutMillis, effectiveTimeout), false);
    }

    public void injectPendingMessages(AgentExecutionContext context) throws IOException {
        AgentNode node = requireNode(context.threadId());
        List<InterAgentMessage> messages = new ArrayList<>();
        synchronized (node.mailbox) {
            while (!node.mailbox.isEmpty()) {
                messages.add(node.mailbox.removeFirst());
            }
        }
        for (InterAgentMessage message : messages) {
            sessionStore.appendItem(node.session, message.toModelItem(mapper));
        }
    }

    public AgentStatus status(String target, AgentExecutionContext sender) {
        return resolve(sender, target).status.get();
    }

    private void deliver(AgentExecutionContext sender, String target, String message, boolean triggerTurn) {
        deliver(sender, resolve(sender, target), message, triggerTurn);
    }

    private void deliver(
            AgentExecutionContext sender,
            AgentNode receiver,
            String message,
            boolean triggerTurn
    ) {
        requireMessage(message);
        InterAgentMessage communication = triggerTurn
                ? InterAgentMessage.newTask(sender.agentPath(), receiver.path, message)
                : InterAgentMessage.message(sender.agentPath(), receiver.path, message);
        enqueue(receiver, communication);
    }

    private void startPendingTurn(AgentNode node) {
        synchronized (node) {
            Future<?> active = node.activeTask.get();
            if (active != null && !active.isDone()) {
                return;
            }
            reserveExecutionSlot();
            try {
                submitReservedTurn(node);
            } catch (RuntimeException error) {
                executionSlots.release();
                throw error;
            }
        }
    }

    private void submitReservedTurn(AgentNode node) {
        node.activeTask.set(executor.submit(() -> runNode(node)));
    }

    private void reserveExecutionSlot() {
        if (!executionSlots.tryAcquire()) {
            throw new IllegalStateException(
                    "agent limit reached: max threads " + rootConfig.multiAgent().maxConcurrentThreads()
            );
        }
    }

    private void runNode(AgentNode node) {
        try {
            node.status.set(new AgentStatus.Running());
            CodexAgent agent = agentFactory.create(node.config, this);
            injectPendingMessages(node.context(this));
            CodexAgent.TurnResult result = agent.continueTurn(node.context(this), ignored -> {
            });
            completeNode(node, new AgentStatus.Completed(result.lastAgentMessage()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            node.status.set(new AgentStatus.Interrupted());
            signalActivity(node);
        } catch (Exception error) {
            completeNode(node, new AgentStatus.Errored(error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage()));
        } finally {
            node.activeTask.set(null);
            executionSlots.release();
            if (hasTriggerTurnMail(node)) {
                try {
                    startPendingTurn(node);
                } catch (IllegalStateException ignored) {
                    node.status.set(new AgentStatus.Interrupted());
                }
            }
        }
    }

    private void completeNode(AgentNode node, AgentStatus status) {
        node.status.set(status);
        signalActivity(node);
        if (node.parentPath == null) {
            return;
        }
        AgentNode parent = agentsByPath.get(node.parentPath);
        if (parent == null) {
            return;
        }
        InterAgentMessage completion = InterAgentMessage.completion(node.path, parent.path, status);
        enqueue(parent, completion);
    }

    private ArrayNode forkHistory(
            SessionStore.Session parentSession,
            String spawnCallId,
            ForkMode mode
    ) {
        ArrayNode source = sessionStore.snapshotInput(parentSession);
        if (spawnCallId != null && !spawnCallId.isBlank() && !source.isEmpty()) {
            JsonNode last = source.get(source.size() - 1);
            if (spawnCallId.equals(last.path("call_id").asText())) {
                source.remove(source.size() - 1);
            }
        }
        if (mode instanceof ForkMode.None) {
            return mapper.createArrayNode();
        }
        if (mode instanceof ForkMode.All) {
            return source;
        }
        int turns = ((ForkMode.Last) mode).count();
        List<Integer> boundaries = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            JsonNode item = source.get(index);
            if ("message".equals(item.path("type").asText())
                    && "user".equals(item.path("role").asText())) {
                boundaries.add(index);
            }
        }
        if (boundaries.isEmpty()) {
            return mapper.createArrayNode();
        }
        int boundary = boundaries.get(Math.max(0, boundaries.size() - turns));
        ArrayNode result = mapper.createArrayNode();
        for (int index = boundary; index < source.size(); index++) {
            result.add(source.get(index).deepCopy());
        }
        return result;
    }

    private CodexConfig childConfig(ForkMode forkMode, String requestedModel) {
        String model = forkMode instanceof ForkMode.All || requestedModel == null || requestedModel.isBlank()
                ? rootConfig.model()
                : requestedModel;
        return new CodexConfig(
                model,
                rootConfig.baseUrl(),
                rootConfig.apiKey(),
                rootConfig.codexHome(),
                rootConfig.workingDirectory(),
                rootConfig.approvalPolicy(),
                rootConfig.sandboxMode(),
                rootConfig.requestTimeout(),
                rootConfig.maxTurns(),
                rootConfig.maxOutputCharacters(),
                rootConfig.multiAgent()
        );
    }

    private AgentNode resolve(AgentExecutionContext sender, String target) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("agent path must not be empty");
        }
        AgentNode byId = agentsById.get(target);
        if (byId != null) {
            return byId;
        }
        AgentPath path = sender.agentPath().resolve(target);
        AgentNode node = agentsByPath.get(path);
        if (node == null) {
            throw new IllegalArgumentException("live agent path `" + path + "` not found");
        }
        return node;
    }

    private AgentNode requireNode(String threadId) {
        AgentNode node = agentsById.get(threadId);
        if (node == null) {
            throw new IllegalArgumentException("agent with id " + threadId + " not found");
        }
        return node;
    }

    private static void enqueue(AgentNode node, InterAgentMessage message) {
        synchronized (node.mailbox) {
            node.mailbox.addLast(message);
        }
        signalActivity(node);
    }

    private static void signalActivity(AgentNode node) {
        node.activityVersion.incrementAndGet();
        synchronized (node.activityMonitor) {
            node.activityMonitor.notifyAll();
        }
    }

    private static boolean hasTriggerTurnMail(AgentNode node) {
        synchronized (node.mailbox) {
            return node.mailbox.stream().anyMatch(InterAgentMessage::triggerTurn);
        }
    }

    private static String clampedMessage(String message, Long requested, long effective) {
        return requested != null && requested < effective
                ? message + "\n\nRequested timeout of " + requested
                + "ms was clamped to the minimum of " + effective + "ms."
                : message;
    }

    private static void requireMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty message can't be sent to an agent");
        }
    }

    @Override
    public void close() {
        for (AgentNode node : agentsByPath.values()) {
            Future<?> task = node.activeTask.getAndSet(null);
            if (task != null) {
                task.cancel(true);
            }
        }
        executor.shutdownNow();
    }

    public interface AgentFactory {
        CodexAgent create(CodexConfig config, MultiAgentManager manager) throws IOException;
    }

    public record SpawnResult(String taskName, String nickname) {
    }

    public record ListedAgent(String agentName, AgentStatus agentStatus) {
    }

    public record WaitResult(String message, boolean timedOut) {
    }

    private sealed interface ForkMode permits ForkMode.None, ForkMode.All, ForkMode.Last {
        static ForkMode parse(String value) {
            String normalized = value == null || value.isBlank() ? "all" : value.trim();
            if ("none".equalsIgnoreCase(normalized)) {
                return new None();
            }
            if ("all".equalsIgnoreCase(normalized)) {
                return new All();
            }
            try {
                int count = Integer.parseInt(normalized);
                if (count < 1) {
                    throw new NumberFormatException();
                }
                return new Last(count);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        "fork_turns must be `none`, `all`, or a positive integer string"
                );
            }
        }

        record None() implements ForkMode {
        }

        record All() implements ForkMode {
        }

        record Last(int count) implements ForkMode {
        }
    }

    private static final class AgentNode {
        private final String threadId;
        private final AgentPath path;
        private final AgentPath parentPath;
        private final SessionStore.Session session;
        private final CodexConfig config;
        private final AtomicReference<AgentStatus> status;
        private final AtomicReference<Future<?>> activeTask = new AtomicReference<>();
        private final ArrayDeque<InterAgentMessage> mailbox = new ArrayDeque<>();
        private final Object activityMonitor = new Object();
        private final AtomicLong activityVersion = new AtomicLong();

        private AgentNode(
                String threadId,
                AgentPath path,
                AgentPath parentPath,
                SessionStore.Session session,
                CodexConfig config,
                AgentStatus status
        ) {
            this.threadId = threadId;
            this.path = path;
            this.parentPath = parentPath;
            this.session = session;
            this.config = config;
            this.status = new AtomicReference<>(status);
        }

        private AgentExecutionContext context(MultiAgentManager manager) {
            return new AgentExecutionContext(threadId, path, session, manager, null);
        }
    }
}
