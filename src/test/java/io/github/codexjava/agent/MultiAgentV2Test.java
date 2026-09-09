package io.github.codexjava.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.codexjava.api.ResponsesApiClient;
import io.github.codexjava.config.CodexConfig;
import io.github.codexjava.tools.MultiAgentTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentV2Test {
    @TempDir
    Path temporaryDirectory;

    @Test
    void interAgentMessagesUseStandardResponsesInputType() {
        ObjectMapper mapper = new ObjectMapper();

        JsonNode item = InterAgentMessage.newTask(
                AgentPath.root(),
                AgentPath.root().join("worker"),
                "inspect the project"
        ).toModelItem(mapper);

        assertEquals("message", item.path("type").asText());
        assertEquals("user", item.path("role").asText());
        assertEquals("input_text", item.path("content").get(0).path("type").asText());
        assertTrue(item.path("content").get(0).path("text").asText().contains("Message Type: NEW_TASK"));
        assertFalse(item.has("author"));
        assertFalse(item.has("recipient"));
    }

    @Test
    void agentPathMatchesCodexV2ValidationAndResolution() {
        AgentPath child = AgentPath.root().join("research_1");
        assertEquals("/root/research_1", child.toString());
        assertEquals("/root/research_1/worker", child.resolve("worker").toString());
        assertEquals("/root/peer", child.resolve("/root/peer").toString());
        assertThrows(IllegalArgumentException.class, () -> AgentPath.root().join("Bad-Name"));
        assertThrows(IllegalArgumentException.class, () -> child.resolve("../peer"));
        assertThrows(IllegalArgumentException.class, () -> new AgentPath("/other"));
    }

    @Test
    void waitTimeoutIsClampedLikeCodexV2() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CodexConfig config = config(URI.create("http://127.0.0.1:1/v1"));
        SessionStore store = new SessionStore(mapper, config.codexHome());
        try (MultiAgentManager manager = new MultiAgentManager(
                mapper,
                store,
                config,
                (childConfig, childManager) -> {
                    throw new AssertionError("no child should be created");
                }
        )) {
            AgentExecutionContext root = manager.registerRoot(store.create(
                    config.workingDirectory(),
                    config.model()
            ));
            long started = System.nanoTime();
            MultiAgentManager.WaitResult result = manager.waitForActivity(root, 1L);
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertTrue(result.timedOut());
            assertTrue(result.message().contains("clamped to the minimum of 10000ms"));
            assertTrue(elapsedMillis >= 9_000);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.waitForActivity(root, 3_600_001L)
            );
        }
    }

    @Test
    void rootSpawnsChildWaitsAndReceivesFinalAnswer() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            ArrayNode input = (ArrayNode) request.path("input");
            if (containsText(input, "Message Type: NEW_TASK")) {
                sendTextResponse(exchange, "child-response", "child result");
            } else if (containsType(input, "function_call_output", "spawn-1")
                    && !containsType(input, "function_call_output", "wait-1")) {
                sendFunctionCall(
                        exchange,
                        "wait-1",
                        "wait_agent",
                        "collaboration",
                        "{\"timeout_ms\":10000}"
                );
            } else if (containsType(input, "function_call_output", "wait-1")) {
                assertTrue(containsText(input, "Message Type: FINAL_ANSWER"));
                assertTrue(containsText(input, "child result"));
                sendTextResponse(exchange, "root-final", "integrated child result");
            } else {
                sendFunctionCall(
                        exchange,
                        "spawn-1",
                        "spawn_agent",
                        "collaboration",
                        "{\"task_name\":\"research\",\"message\":\"research independently\",\"fork_turns\":\"none\"}"
                );
            }
        });
        server.start();
        try {
            CodexConfig config = config(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"
            ));
            SessionStore store = new SessionStore(mapper, config.codexHome());
            MultiAgentManager.AgentFactory factory = (childConfig, manager) -> new CodexAgent(
                    childConfig,
                    mapper,
                    new ResponsesApiClient(childConfig, mapper),
                    store,
                    "test",
                    MultiAgentTools.create(mapper, childConfig.multiAgent())
            );
            try (MultiAgentManager manager = new MultiAgentManager(mapper, store, config, factory)) {
                CodexAgent rootAgent = new CodexAgent(
                        config,
                        mapper,
                        new ResponsesApiClient(config, mapper),
                        store,
                        "test",
                        MultiAgentTools.create(mapper, config.multiAgent())
                );
                SessionStore.Session rootSession = rootAgent.newSession();
                AgentExecutionContext root = manager.registerRoot(rootSession);
                CodexAgent.TurnResult result = rootAgent.runTurn(
                        root,
                        userMessage(mapper, "delegate this"),
                        ignored -> {
                        }
                );

                assertEquals("integrated child result", result.text());
                assertEquals(2, result.toolCalls());
                assertTrue(requests.size() >= 4);
                JsonNode firstTools = requests.get(0).path("tools");
                JsonNode spawnAgent = findByName(firstTools, "spawn_agent");
                assertEquals("function", spawnAgent.path("type").asText());
                JsonNode spawnProperties = spawnAgent
                        .path("parameters")
                        .path("properties");
                assertTrue(spawnProperties.has("task_name"));
                assertTrue(spawnProperties.has("fork_turns"));
                assertFalse(spawnProperties.has("fork_context"));
                assertFalse(spawnProperties.has("model"));
                assertFalse(spawnProperties.has("agent_type"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void duplicateTaskNamesAndInvalidForkModesAreRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch releaseChild = new CountDownLatch(1);
        server.createContext("/v1/responses", exchange -> {
            try {
                releaseChild.await(5, TimeUnit.SECONDS);
                sendTextResponse(exchange, "child", "done");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();
        try {
            CodexConfig config = config(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"
            ));
            SessionStore store = new SessionStore(mapper, config.codexHome());
            MultiAgentManager.AgentFactory factory = (childConfig, manager) -> new CodexAgent(
                    childConfig,
                    mapper,
                    new ResponsesApiClient(childConfig, mapper),
                    store,
                    "test",
                    MultiAgentTools.create(mapper, childConfig.multiAgent())
            );
            try (MultiAgentManager manager = new MultiAgentManager(mapper, store, config, factory)) {
                AgentExecutionContext root = manager.registerRoot(store.create(
                        config.workingDirectory(),
                        config.model()
                ));
                manager.spawn(root, "worker", "first", "none", null, null, null, null);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.spawn(root, "worker", "duplicate", "none", null, null, null, null)
                );
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.spawn(root, "other", "bad fork", "0", null, null, null, null)
                );
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.spawn(root, "other", "legacy", null, null, null, null, true)
                );
                assertThrows(
                        IllegalArgumentException.class,
                        () -> manager.followupTask(root, "/root", "not allowed")
                );
            } finally {
                releaseChild.countDown();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void concurrentChildLimitFailsImmediately() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                sendTextResponse(exchange, "child", "done");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();
        try {
            CodexConfig base = config(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"
            ));
            CodexConfig config = new CodexConfig(
                    base.model(),
                    base.baseUrl(),
                    base.apiKey(),
                    base.codexHome(),
                    base.workingDirectory(),
                    base.approvalPolicy(),
                    base.sandboxMode(),
                    base.requestTimeout(),
                    base.maxTurns(),
                    base.maxOutputCharacters(),
                    new CodexConfig.MultiAgentConfig(true, 2, 10_000, 3_600_000, 30_000, "collaboration")
            );
            SessionStore store = new SessionStore(mapper, config.codexHome());
            MultiAgentManager.AgentFactory factory = (childConfig, manager) -> new CodexAgent(
                    childConfig,
                    mapper,
                    new ResponsesApiClient(childConfig, mapper),
                    store,
                    "test",
                    MultiAgentTools.create(mapper, childConfig.multiAgent())
            );
            try (MultiAgentManager manager = new MultiAgentManager(mapper, store, config, factory)) {
                AgentExecutionContext root = manager.registerRoot(store.create(
                        config.workingDirectory(),
                        config.model()
                ));
                manager.spawn(root, "one", "first", "none", null, null, null, null);
                assertTrue(started.await(2, TimeUnit.SECONDS));
                IllegalStateException error = assertThrows(
                        IllegalStateException.class,
                        () -> manager.spawn(root, "two", "second", "none", null, null, null, null)
                );
                assertTrue(error.getMessage().contains("agent limit reached"));
                assertEquals(2, manager.listAgents(root, null).size());
            } finally {
                release.countDown();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredThreadLimitIncludesRootAgent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CodexConfig base = config(URI.create("http://127.0.0.1:1/v1"));
        CodexConfig config = new CodexConfig(
                base.model(),
                base.baseUrl(),
                base.apiKey(),
                base.codexHome(),
                base.workingDirectory(),
                base.approvalPolicy(),
                base.sandboxMode(),
                base.requestTimeout(),
                base.maxTurns(),
                base.maxOutputCharacters(),
                new CodexConfig.MultiAgentConfig(true, 1, 10_000, 3_600_000, 30_000, "collaboration")
        );
        SessionStore store = new SessionStore(mapper, config.codexHome());
        try (MultiAgentManager manager = new MultiAgentManager(
                mapper,
                store,
                config,
                (childConfig, childManager) -> {
                    throw new AssertionError("no child should be created");
                }
        )) {
            AgentExecutionContext root = manager.registerRoot(store.create(
                    config.workingDirectory(),
                    config.model()
            ));
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> manager.spawn(root, "worker", "task", "none", null, null, null, null)
            );
            assertEquals("agent limit reached: max threads 1", error.getMessage());
        }
    }

    @Test
    void queueOnlyMessageWaitsUntilFollowupStartsNextTurn() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch finishFirstTurn = new CountDownLatch(1);
        CountDownLatch secondRequest = new CountDownLatch(1);
        AtomicInteger requestNumber = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            if (requestNumber.incrementAndGet() == 1) {
                firstRequest.countDown();
                try {
                    finishFirstTurn.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    return;
                }
                sendTextResponse(exchange, "first", "initial complete");
            } else {
                secondRequest.countDown();
                sendTextResponse(exchange, "second", "follow-up complete");
            }
        });
        server.start();
        try {
            CodexConfig config = config(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"
            ));
            SessionStore store = new SessionStore(mapper, config.codexHome());
            MultiAgentManager.AgentFactory factory = agentFactory(mapper, store);
            try (MultiAgentManager manager = new MultiAgentManager(mapper, store, config, factory)) {
                AgentExecutionContext root = manager.registerRoot(store.create(
                        config.workingDirectory(),
                        config.model()
                ));
                manager.spawn(root, "worker", "initial task", "none", null, null, null, null);
                assertTrue(firstRequest.await(2, TimeUnit.SECONDS));
                manager.sendMessage(root, "worker", "queued context");
                Thread.sleep(100);
                assertEquals(1, requests.size());

                finishFirstTurn.countDown();
                awaitStatus(manager, root, "worker", AgentStatus.Completed.class);
                manager.followupTask(root, "worker", "next task");
                assertTrue(secondRequest.await(2, TimeUnit.SECONDS));

                JsonNode secondInput = requests.get(1).path("input");
                assertTrue(containsText(secondInput, "Message Type: MESSAGE"));
                assertTrue(containsText(secondInput, "queued context"));
                assertTrue(containsText(secondInput, "Message Type: NEW_TASK"));
                assertTrue(containsText(secondInput, "next task"));
            } finally {
                finishFirstTurn.countDown();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interruptReturnsPreviousStatusAndMarksAgentInterrupted() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch holdRequest = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestStarted.countDown();
            try {
                holdRequest.await(5, TimeUnit.SECONDS);
                sendTextResponse(exchange, "late", "late result");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();
        try {
            CodexConfig config = config(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"
            ));
            SessionStore store = new SessionStore(mapper, config.codexHome());
            try (MultiAgentManager manager = new MultiAgentManager(
                    mapper,
                    store,
                    config,
                    agentFactory(mapper, store)
            )) {
                AgentExecutionContext root = manager.registerRoot(store.create(
                        config.workingDirectory(),
                        config.model()
                ));
                manager.spawn(root, "worker", "long task", "none", null, null, null, null);
                assertTrue(requestStarted.await(2, TimeUnit.SECONDS));
                AgentStatus previous = manager.interrupt(root, "worker");
                assertTrue(previous instanceof AgentStatus.Running);
                awaitStatus(manager, root, "worker", AgentStatus.Interrupted.class);
            } finally {
                holdRequest.countDown();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lastNForkKeepsOnlyRequestedTurnAndDropsSpawnCall() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            received.countDown();
            sendTextResponse(exchange, "child", "done");
        });
        server.start();
        try {
            CodexConfig config = config(URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"
            ));
            SessionStore store = new SessionStore(mapper, config.codexHome());
            try (MultiAgentManager manager = new MultiAgentManager(
                    mapper,
                    store,
                    config,
                    agentFactory(mapper, store)
            )) {
                SessionStore.Session rootSession = store.create(config.workingDirectory(), config.model());
                store.appendItem(rootSession, userMessage(mapper, "old turn"));
                store.appendItem(rootSession, assistantMessage(mapper, "old answer"));
                store.appendItem(rootSession, userMessage(mapper, "current turn"));
                store.appendItem(rootSession, functionCall(mapper, "spawn-call"));
                AgentExecutionContext root = manager.registerRoot(rootSession).forToolCall("spawn-call");

                manager.spawn(root, "worker", "forked task", "1", null, null, null, null);
                assertTrue(received.await(2, TimeUnit.SECONDS));
                JsonNode input = requests.get(0).path("input");
                assertFalse(containsText(input, "old turn"));
                assertFalse(containsText(input, "old answer"));
                assertTrue(containsText(input, "current turn"));
                assertFalse(containsType(input, "function_call", "spawn-call"));
                assertTrue(containsText(input, "forked task"));
            }
        } finally {
            server.stop(0);
        }
    }

    private CodexConfig config(URI baseUrl) {
        return new CodexConfig(
                "test-model",
                baseUrl,
                "test-key",
                temporaryDirectory.resolve(".codex"),
                temporaryDirectory,
                CodexConfig.ApprovalPolicy.NEVER,
                CodexConfig.SandboxMode.WORKSPACE_WRITE,
                Duration.ofSeconds(20),
                8,
                10_000,
                new CodexConfig.MultiAgentConfig(true, 4, 10_000, 3_600_000, 30_000, "collaboration")
        );
    }

    private MultiAgentManager.AgentFactory agentFactory(ObjectMapper mapper, SessionStore store) {
        return (childConfig, manager) -> new CodexAgent(
                childConfig,
                mapper,
                new ResponsesApiClient(childConfig, mapper),
                store,
                "test",
                MultiAgentTools.create(mapper, childConfig.multiAgent())
        );
    }

    private static void awaitStatus(
            MultiAgentManager manager,
            AgentExecutionContext root,
            String target,
            Class<? extends AgentStatus> expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (expected.isInstance(manager.status(target, root))) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(expected.isInstance(manager.status(target, root)));
    }

    private static JsonNode userMessage(ObjectMapper mapper, String text) {
        JsonNode message = mapper.createObjectNode()
                .put("type", "message")
                .put("role", "user");
        ((com.fasterxml.jackson.databind.node.ObjectNode) message)
                .putArray("content")
                .addObject()
                .put("type", "input_text")
                .put("text", text);
        return message;
    }

    private static JsonNode assistantMessage(ObjectMapper mapper, String text) {
        JsonNode message = mapper.createObjectNode()
                .put("type", "message")
                .put("role", "assistant");
        ((com.fasterxml.jackson.databind.node.ObjectNode) message)
                .putArray("content")
                .addObject()
                .put("type", "output_text")
                .put("text", text);
        return message;
    }

    private static JsonNode functionCall(ObjectMapper mapper, String callId) {
        return mapper.createObjectNode()
                .put("type", "function_call")
                .put("call_id", callId)
                .put("name", "spawn_agent")
                .put("arguments", "{}");
    }

    private static boolean containsText(JsonNode input, String expected) {
        return input.toString().contains(expected);
    }

    private static boolean containsType(JsonNode input, String type, String callId) {
        for (JsonNode item : input) {
            if (type.equals(item.path("type").asText()) && callId.equals(item.path("call_id").asText())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode findByName(JsonNode values, String name) {
        for (JsonNode value : values) {
            if (name.equals(value.path("name").asText())) {
                return value;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static void sendFunctionCall(
            HttpExchange exchange,
            String callId,
            String name,
            String namespace,
            String arguments
    ) throws IOException {
        String escapedArguments = new ObjectMapper().writeValueAsString(arguments);
        sendSse(exchange, """
                data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"%s","name":"%s","namespace":"%s","arguments":%s}}

                data: {"type":"response.completed","response":{"id":"response-%s","usage":null}}

                """.formatted(callId, name, namespace, escapedArguments, callId));
    }

    private static void sendTextResponse(HttpExchange exchange, String responseId, String text)
            throws IOException {
        sendSse(exchange, """
                data: {"type":"response.output_text.delta","delta":"%s"}

                data: {"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"%s"}]}}

                data: {"type":"response.completed","response":{"id":"%s","usage":null}}

                """.formatted(text, text, responseId));
    }

    private static void sendSse(HttpExchange exchange, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
