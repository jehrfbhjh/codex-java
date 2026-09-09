package io.github.codexjava.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.codexjava.api.ResponsesApiClient;
import io.github.codexjava.config.CodexConfig;
import io.github.codexjava.tools.ApplyPatchTool;
import io.github.codexjava.tools.ApprovalGate;
import io.github.codexjava.tools.ExecCommandTool;
import io.github.codexjava.tools.WorkspacePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAgentTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void executesFunctionCallAndReturnsOutputToModel() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger requestCount = new AtomicInteger();
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            if (requestCount.getAndIncrement() == 0) {
                sendSse(exchange, """
                        data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"call-1","name":"exec_command","arguments":"{\\"cmd\\":\\"printf hello\\"}"}}

                        data: {"type":"response.completed","response":{"id":"resp-1","usage":null}}

                        """);
            } else {
                sendSse(exchange, """
                        data: {"type":"response.output_text.delta","delta":"done"}

                        data: {"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}}

                        data: {"type":"response.completed","response":{"id":"resp-2","usage":null}}

                        """);
            }
        });
        server.start();
        try {
            CodexConfig config = new CodexConfig(
                    "test-model",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    "test-key",
                    temporaryDirectory.resolve(".codex"),
                    temporaryDirectory,
                    CodexConfig.ApprovalPolicy.NEVER,
                    CodexConfig.SandboxMode.WORKSPACE_WRITE,
                    Duration.ofSeconds(10),
                    4,
                    10_000
            );
            SessionStore store = new SessionStore(mapper, config.codexHome());
            WorkspacePolicy workspacePolicy = new WorkspacePolicy(temporaryDirectory, config.sandboxMode());
            CodexAgent agent = new CodexAgent(
                    config,
                    mapper,
                    new ResponsesApiClient(config, mapper),
                    store,
                    "test instructions",
                    List.of(
                            new ExecCommandTool(
                                    mapper,
                                    workspacePolicy,
                                    new ApprovalGate(config.approvalPolicy()),
                                    config.maxOutputCharacters()
                            ),
                            new ApplyPatchTool(mapper, workspacePolicy)
                    )
            );

            StringBuilder output = new StringBuilder();
            List<ObjectNode> events = new ArrayList<>();
            CodexAgent.TurnResult result = agent.runTurn(
                    agent.newSession(),
                    "say hello",
                    output::append,
                    event -> events.add(event.deepCopy())
            );

            assertEquals("done", result.text());
            assertEquals("done", output.toString());
            assertEquals(1, result.toolCalls());
            assertEquals(2, requests.size());
            JsonNode applyPatch = requests.get(0).path("tools").get(1);
            assertEquals("apply_patch", applyPatch.path("name").asText());
            assertEquals("function", applyPatch.path("type").asText());
            assertEquals("string", applyPatch.path("parameters").path("properties").path("patch").path("type").asText());
            JsonNode secondInput = requests.get(1).path("input");
            JsonNode toolOutput = secondInput.get(secondInput.size() - 1);
            assertEquals("function_call_output", toolOutput.path("type").asText());
            assertEquals("call-1", toolOutput.path("call_id").asText());
            assertTrue(toolOutput.path("output").asText().contains("hello"));
            assertTrue(events.stream().anyMatch(event ->
                    "item.updated".equals(event.path("type").asText())
                            && "command_execution".equals(event.path("item").path("type").asText())
                            && event.path("item").path("delta").asText().contains("hello")
            ));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsReasoningLifecycleAndUsageEvents() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> sendSse(exchange, """
                data: {"type":"response.created","response":{"id":"resp-stream"}}

                data: {"type":"response.reasoning_summary_part.added","summary_index":0}

                data: {"type":"response.reasoning_summary_text.delta","summary_index":0,"delta":"Inspecting"}

                data: {"type":"response.reasoning_summary_text.done","item_id":"reasoning-1","summary_index":0,"text":"Inspecting"}

                data: {"type":"response.output_text.delta","delta":"done"}

                data: {"type":"response.output_item.done","item":{"id":"message-1","type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}}

                data: {"type":"response.completed","response":{"id":"resp-stream","usage":{"input_tokens":12,"input_tokens_details":{"cached_tokens":3},"output_tokens":5,"output_tokens_details":{"reasoning_tokens":2},"total_tokens":17}}}

                """));
        server.start();
        try {
            CodexConfig config = new CodexConfig(
                    "test-model",
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                    "test-key",
                    temporaryDirectory.resolve(".codex"),
                    temporaryDirectory,
                    CodexConfig.ApprovalPolicy.NEVER,
                    CodexConfig.SandboxMode.WORKSPACE_WRITE,
                    Duration.ofSeconds(10),
                    4,
                    10_000
            );
            SessionStore store = new SessionStore(mapper, config.codexHome());
            CodexAgent agent = new CodexAgent(
                    config,
                    mapper,
                    new ResponsesApiClient(config, mapper),
                    store,
                    "test instructions",
                    List.of()
            );
            List<ObjectNode> events = new ArrayList<>();

            CodexAgent.TurnResult result = agent.runTurn(
                    agent.newSession(),
                    "inspect",
                    ignored -> {
                    },
                    event -> events.add(event.deepCopy())
            );

            assertEquals("done", result.text());
            assertEquals(12, result.usage().inputTokens());
            assertEquals(3, result.usage().cachedInputTokens());
            assertEquals(5, result.usage().outputTokens());
            assertEquals(2, result.usage().reasoningOutputTokens());
            assertEquals(
                    List.of(
                            "turn.started",
                            "item.started",
                            "item.updated",
                            "item.completed",
                            "item.updated",
                            "item.completed",
                            "turn.completed"
                    ),
                    events.stream()
                            .map(event -> event.path("type").asText())
                            .collect(Collectors.toList())
            );
            ObjectNode completed = events.get(events.size() - 1);
            assertEquals(17, completed.path("usage").path("total_tokens").asLong());
        } finally {
            server.stop(0);
        }
    }

    private static void sendSse(HttpExchange exchange, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
