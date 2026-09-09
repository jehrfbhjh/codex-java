package io.github.codexjava.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.codexjava.api.ResponsesApiClient;
import io.github.codexjava.config.CodexConfig;
import io.github.codexjava.tools.CodexTool;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class CodexAgent {
    private final CodexConfig config;
    private final ObjectMapper mapper;
    private final ResponsesApiClient apiClient;
    private final SessionStore sessionStore;
    private final String instructions;
    private final Map<String, CodexTool> tools;

    public CodexAgent(
            CodexConfig config,
            ObjectMapper mapper,
            ResponsesApiClient apiClient,
            SessionStore sessionStore,
            String instructions,
            Iterable<CodexTool> tools
    ) {
        this.config = config;
        this.mapper = mapper;
        this.apiClient = apiClient;
        this.sessionStore = sessionStore;
        this.instructions = instructions;
        this.tools = new LinkedHashMap<>();
        tools.forEach(tool -> this.tools.put(toolKey(tool.namespace(), tool.name()), tool));
    }

    public SessionStore.Session newSession() throws IOException {
        return sessionStore.create(config.workingDirectory(), config.model());
    }

    public TurnResult runTurn(
            SessionStore.Session session,
            String prompt,
            Consumer<String> textDelta
    ) throws IOException, InterruptedException {
        return runTurn(session, prompt, textDelta, AgentEventListener.noop());
    }

    public TurnResult runTurn(
            SessionStore.Session session,
            String prompt,
            Consumer<String> textDelta,
            AgentEventListener eventListener
    ) throws IOException, InterruptedException {
        return runTurn(
                new AgentExecutionContext(session.id(), AgentPath.root(), session, null, null),
                message("user", prompt),
                textDelta,
                eventListener
        );
    }

    public TurnResult runTurn(
            AgentExecutionContext context,
            JsonNode initialInput,
            Consumer<String> textDelta
    ) throws IOException, InterruptedException {
        return runTurn(context, initialInput, textDelta, AgentEventListener.noop());
    }

    public TurnResult runTurn(
            AgentExecutionContext context,
            JsonNode initialInput,
            Consumer<String> textDelta,
            AgentEventListener eventListener
    ) throws IOException, InterruptedException {
        SessionStore.Session session = context.session();
        sessionStore.appendItem(session, initialInput);
        return continueTurn(context, textDelta, eventListener);
    }

    public TurnResult continueTurn(
            AgentExecutionContext context,
            Consumer<String> textDelta
    ) throws IOException, InterruptedException {
        return continueTurn(context, textDelta, AgentEventListener.noop());
    }

    public TurnResult continueTurn(
            AgentExecutionContext context,
            Consumer<String> textDelta,
            AgentEventListener eventListener
    ) throws IOException, InterruptedException {
        SessionStore.Session session = context.session();
        StringBuilder visibleText = new StringBuilder();
        int toolCalls = 0;
        ResponsesApiClient.Usage totalUsage = ResponsesApiClient.Usage.empty();
        emit(eventListener, event(eventListener, context, "turn.started"));

        try {
            for (int turn = 0; turn < config.maxTurns(); turn++) {
                if (context.multiAgentManager() != null && config.multiAgent().enabled()) {
                    context.multiAgentManager().injectPendingMessages(context);
                }
                StringBuilder responseText = new StringBuilder();
                ResponsesApiClient.ResponseResult response = apiClient.createResponse(
                        instructionsFor(context),
                        sessionStore.snapshotInput(session),
                        toolSpecifications(),
                        delta -> {
                            responseText.append(delta);
                            visibleText.append(delta);
                            textDelta.accept(delta);
                        },
                        streamEvent -> emitStreamEvent(eventListener, context, streamEvent)
                );
                totalUsage = addUsage(totalUsage, response.usage());

                boolean calledTool = false;
                for (JsonNode item : response.outputItems()) {
                    sessionStore.appendItem(session, item);
                    String type = item.path("type").asText();
                    if ("function_call".equals(type) || "custom_tool_call".equals(type)) {
                        calledTool = true;
                        toolCalls++;
                        emitToolStarted(eventListener, context, item);
                        ToolExecution execution = executeTool(
                                item,
                                context.withEventListener(eventListener).forToolCall(
                                        item.path("call_id").asText(item.path("id").asText())
                                )
                        );
                        sessionStore.appendItem(session, execution.output());
                        emitToolCompleted(eventListener, context, item, execution);
                    }
                }
                if (!calledTool) {
                    ObjectNode completed = event(eventListener, context, "turn.completed");
                    completed.set("usage", usageNode(totalUsage));
                    completed.put("tool_calls", toolCalls);
                    emit(eventListener, completed);
                    return new TurnResult(
                            session.id(),
                            response.responseId(),
                            visibleText.toString(),
                            responseText.toString(),
                            toolCalls,
                            totalUsage
                    );
                }
            }
            throw new IOException("Agent exceeded max tool turns: " + config.maxTurns());
        } catch (IOException | InterruptedException | RuntimeException error) {
            ObjectNode failed = event(eventListener, context, "turn.failed");
            failed.put("message", Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
            emit(eventListener, failed);
            throw error;
        }
    }

    private ToolExecution executeTool(JsonNode call, AgentExecutionContext context) {
        String name = call.path("name").asText();
        String namespace = call.path("namespace").asText(null);
        String callId = call.path("call_id").asText(call.path("id").asText());
        CodexTool tool = tools.get(toolKey(namespace, name));
        if (tool == null && namespace == null) {
            tool = tools.values().stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst()
                    .orElse(null);
        }
        CodexTool.ToolResult result;
        if (tool == null) {
            result = CodexTool.ToolResult.failure("Unsupported tool: " + name);
        } else {
            try {
                JsonNode arguments = parseArguments(call);
                result = tool.execute(arguments, context);
            } catch (Exception error) {
                result = CodexTool.ToolResult.failure(
                        "Tool " + name + " failed: " + error.getClass().getSimpleName() + ": " + error.getMessage()
                );
            }
        }

        ObjectNode output = mapper.createObjectNode();
        output.put(
                "type",
                "custom_tool_call".equals(call.path("type").asText())
                        ? "custom_tool_call_output"
                        : "function_call_output"
        );
        output.put("call_id", callId);
        output.put("output", result.output());
        return new ToolExecution(output, result.success());
    }

    private JsonNode parseArguments(JsonNode call) throws IOException {
        JsonNode arguments = call.get("arguments");
        if (arguments != null && arguments.isObject()) {
            return arguments;
        }
        if (arguments != null && arguments.isTextual()) {
            String text = arguments.asText();
            return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
        }
        JsonNode input = call.get("input");
        if (input != null && input.isTextual()) {
            return input;
        }
        return mapper.createObjectNode();
    }

    private void emitStreamEvent(
            AgentEventListener listener,
            AgentExecutionContext context,
            ResponsesApiClient.StreamEvent streamEvent
    ) {
        if (streamEvent instanceof ResponsesApiClient.StreamEvent.OutputTextDelta text) {
            ObjectNode event = event(listener, context, "item.updated");
            event.set("item", item(
                    "assistant_message",
                    "agent_message",
                    "delta",
                    text.delta()
            ));
            emit(listener, event);
        } else if (streamEvent instanceof ResponsesApiClient.StreamEvent.ReasoningSummaryPartAdded reasoning) {
            ObjectNode event = event(listener, context, "item.started");
            event.set("item", item(
                    "reasoning_" + reasoning.summaryIndex(),
                    "reasoning",
                    "text",
                    ""
            ));
            emit(listener, event);
        } else if (streamEvent instanceof ResponsesApiClient.StreamEvent.ReasoningSummaryDelta reasoning) {
            ObjectNode event = event(listener, context, "item.updated");
            event.set("item", item(
                    "reasoning_" + reasoning.summaryIndex(),
                    "reasoning",
                    "delta",
                    reasoning.delta()
            ));
            emit(listener, event);
        } else if (streamEvent instanceof ResponsesApiClient.StreamEvent.ReasoningSummaryDone reasoning) {
            ObjectNode event = event(listener, context, "item.completed");
            event.set("item", item(
                    "reasoning_" + reasoning.summaryIndex(),
                    "reasoning",
                    "text",
                    reasoning.text()
            ));
            emit(listener, event);
        } else if (streamEvent instanceof ResponsesApiClient.StreamEvent.OutputItemDone outputItem) {
            JsonNode responseItem = outputItem.item();
            if ("message".equals(responseItem.path("type").asText())
                    && "assistant".equals(responseItem.path("role").asText())) {
                ObjectNode event = event(listener, context, "item.completed");
                ObjectNode item = mapper.createObjectNode();
                item.put("id", responseItem.path("id").asText("assistant_message"));
                item.put("type", "agent_message");
                item.put("text", messageText(responseItem));
                event.set("item", item);
                emit(listener, event);
            }
        } else if (streamEvent instanceof ResponsesApiClient.StreamEvent.StreamError streamError) {
            ObjectNode event = event(listener, context, "error");
            event.put("message", streamError.message());
            emit(listener, event);
        }
    }

    private void emitToolStarted(
            AgentEventListener listener,
            AgentExecutionContext context,
            JsonNode call
    ) {
        ObjectNode event = event(listener, context, "item.started");
        ObjectNode item = mapper.createObjectNode();
        item.put("id", call.path("call_id").asText(call.path("id").asText()));
        item.put("type", toolItemType(call));
        item.put("tool", call.path("name").asText());
        if (call.hasNonNull("namespace")) {
            item.put("namespace", call.path("namespace").asText());
        }
        JsonNode arguments = call.get("arguments");
        if (arguments != null) {
            item.set("arguments", arguments.deepCopy());
        } else if (call.has("input")) {
            item.set("arguments", call.get("input").deepCopy());
        }
        item.put("status", "in_progress");
        event.set("item", item);
        emit(listener, event);
    }

    private void emitToolCompleted(
            AgentEventListener listener,
            AgentExecutionContext context,
            JsonNode call,
            ToolExecution execution
    ) {
        ObjectNode event = event(listener, context, "item.completed");
        ObjectNode item = mapper.createObjectNode();
        item.put("id", call.path("call_id").asText(call.path("id").asText()));
        item.put("type", toolItemType(call));
        item.put("tool", call.path("name").asText());
        item.put("status", execution.success() ? "completed" : "failed");
        item.put("output", execution.output().path("output").asText());
        event.set("item", item);
        emit(listener, event);
    }

    private static String messageText(JsonNode message) {
        StringBuilder text = new StringBuilder();
        for (JsonNode content : message.path("content")) {
            if ("output_text".equals(content.path("type").asText())) {
                text.append(content.path("text").asText());
            }
        }
        return text.toString();
    }

    private String toolItemType(JsonNode call) {
        String name = call.path("name").asText();
        if ("exec_command".equals(name)) {
            return "command_execution";
        }
        if ("apply_patch".equals(name)) {
            return "file_change";
        }
        if (call.hasNonNull("namespace")) {
            return "collab_tool_call";
        }
        return "tool_call";
    }

    private ObjectNode item(String id, String type, String contentField, String content) {
        ObjectNode item = mapper.createObjectNode();
        item.put("id", id);
        item.put("type", type);
        item.put(contentField, content);
        return item;
    }

    private ObjectNode event(
            AgentEventListener listener,
            AgentExecutionContext context,
            String type
    ) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", type);
        event.put("timestamp", Instant.now().toString());
        event.put("thread_id", context.session().id());
        event.put("agent_path", context.agentPath().toString());
        return event;
    }

    private static void emit(AgentEventListener listener, ObjectNode event) {
        listener.onEvent(event);
    }

    private ObjectNode usageNode(ResponsesApiClient.Usage usage) {
        ObjectNode node = mapper.createObjectNode();
        node.put("input_tokens", usage.inputTokens());
        node.put("cached_input_tokens", usage.cachedInputTokens());
        node.put("output_tokens", usage.outputTokens());
        node.put("reasoning_output_tokens", usage.reasoningOutputTokens());
        node.put("total_tokens", usage.totalTokens());
        return node;
    }

    private static ResponsesApiClient.Usage addUsage(
            ResponsesApiClient.Usage left,
            ResponsesApiClient.Usage right
    ) {
        return new ResponsesApiClient.Usage(
                left.inputTokens() + right.inputTokens(),
                left.cachedInputTokens() + right.cachedInputTokens(),
                left.outputTokens() + right.outputTokens(),
                left.reasoningOutputTokens() + right.reasoningOutputTokens(),
                left.totalTokens() + right.totalTokens()
        );
    }

    private ArrayNode toolSpecifications() {
        ArrayNode specs = mapper.createArrayNode();
        tools.values().forEach(tool -> specs.add(tool.specification()));
        return specs;
    }

    private static String toolKey(String namespace, String name) {
        return Objects.toString(namespace, "") + '\u0000' + name;
    }

    private String instructionsFor(AgentExecutionContext context) {
        if (context.multiAgentManager() == null || !config.multiAgent().enabled()) {
            return instructions;
        }
        if (context.agentPath().isRoot()) {
            return instructions + """

                    You are `/root`, the primary agent in a team of agents collaborating to fulfill
                    the user's goals. You can spawn sub-agents, send messages, assign follow-up
                    tasks, wait for mailbox updates, interrupt agents, and list the agent tree.
                    All agents share the same working directory.
                    """;
        }
        return instructions + """

                You are %s, an agent in a team collaborating to complete a task. You can spawn
                sub-agents and communicate with other agents. When you provide a final response,
                it is automatically delivered to your parent agent. All agents share the same
                working directory.
                """.formatted(context.agentPath());
    }

    private ObjectNode message(String role, String text) {
        ObjectNode message = mapper.createObjectNode();
        message.put("type", "message");
        message.put("role", role);
        ArrayNode content = message.putArray("content");
        ObjectNode textItem = content.addObject();
        textItem.put("type", "input_text");
        textItem.put("text", text);
        return message;
    }

    public record TurnResult(
            String sessionId,
            String responseId,
            String text,
            String lastAgentMessage,
            int toolCalls,
            ResponsesApiClient.Usage usage
    ) {
    }

    private record ToolExecution(JsonNode output, boolean success) {
    }
}
