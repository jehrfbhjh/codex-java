package io.github.codexjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.codexjava.agent.SessionStore;
import io.github.codexjava.cli.CodexRuntime;
import io.github.codexjava.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexWebServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void servesUiAndStreamsTurnsAsNdjson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/v1/responses", exchange -> sendSse(exchange, """
                data: {"type":"response.output_text.delta","delta":"hello"}

                data: {"type":"response.output_item.done","item":{"id":"msg-1","type":"message","role":"assistant","content":[{"type":"output_text","text":"hello"}]}}

                data: {"type":"response.completed","response":{"id":"resp-1","usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}}

                """));
        api.start();

        ConfigLoader.Overrides overrides = new ConfigLoader.Overrides(
                "test-model",
                "http://127.0.0.1:" + api.getAddress().getPort() + "/v1",
                "test-key",
                temporaryDirectory.resolve(".codex"),
                temporaryDirectory,
                "never",
                "workspace-write",
                4
        );
        try (CodexRuntime runtime = CodexRuntime.create(overrides);
             CodexWebServer web = new CodexWebServer(runtime, "127.0.0.1", 0)) {
            web.start();
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + web.port());

            HttpResponse<String> page = client.send(
                    HttpRequest.newBuilder(base.resolve("/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("今天要处理什么"));

            for (String asset : List.of(
                    "/app.css",
                    "/panels.css",
                    "/conversation.css",
                    "/composer.css",
                    "/responsive.css",
                    "/app.js",
                    "/ui.js",
                    "/shell.js"
            )) {
                HttpResponse<String> resource = client.send(
                        HttpRequest.newBuilder(base.resolve(asset)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, resource.statusCode(), asset);
            }

            HttpResponse<String> created = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/threads"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(201, created.statusCode());
            String threadId = mapper.readTree(created.body()).path("thread_id").asText();

            HttpResponse<String> turn = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/threads/" + threadId + "/turn"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"say hello\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, turn.statusCode());
            assertTrue(turn.headers().firstValue("Content-Type").orElse("").contains("application/x-ndjson"));
            List<JsonNode> events = turn.body().lines().map(line -> {
                try {
                    return mapper.readTree(line);
                } catch (IOException error) {
                    throw new IllegalArgumentException(error);
                }
            }).toList();
            assertEquals("turn.started", events.get(0).path("type").asText());
            assertTrue(events.stream().anyMatch(event ->
                    "item.updated".equals(event.path("type").asText())
                            && "hello".equals(event.path("item").path("delta").asText())
            ));
            assertTrue(events.stream().anyMatch(event -> "turn.completed".equals(event.path("type").asText())));
            assertEquals("stream.done", events.get(events.size() - 1).path("type").asText());
            assertEquals(threadId, events.get(events.size() - 1).path("thread_id").asText());

            SessionStore.Session resumedSession = runtime.sessionStore().resume(threadId);
            assertEquals(threadId, resumedSession.id());
            assertTrue(resumedSession.input().size() >= 2);

            HttpResponse<String> resumed = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/threads/" + threadId + "/resume"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, resumed.statusCode());
            assertEquals(threadId, mapper.readTree(resumed.body()).path("thread_id").asText());

            HttpResponse<String> secondCreated = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/threads"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            String secondThreadId = mapper.readTree(secondCreated.body()).path("thread_id").asText();
            assertTrue(!threadId.equals(secondThreadId));

            HttpResponse<String> secondTurn = client.send(
                    HttpRequest.newBuilder(base.resolve("/api/threads/" + secondThreadId + "/turn"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"second task\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            List<JsonNode> secondEvents = secondTurn.body().lines().map(line -> {
                try {
                    return mapper.readTree(line);
                } catch (IOException error) {
                    throw new IllegalArgumentException(error);
                }
            }).toList();
            assertEquals(secondThreadId,
                    secondEvents.get(secondEvents.size() - 1).path("thread_id").asText());
        } finally {
            api.stop(0);
        }
    }

    private static void sendSse(HttpExchange exchange, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
