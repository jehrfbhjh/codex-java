package io.github.codexjava.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.codexjava.config.CodexConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ResponsesApiClient {
    @FunctionalInterface
    public interface StreamEventListener {
        void onEvent(StreamEvent event);

        static StreamEventListener noop() {
            return ignored -> {
            };
        }
    }

    private final CodexConfig config;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public ResponsesApiClient(CodexConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .build();
    }

    public ResponseResult createResponse(
            String instructions,
            ArrayNode input,
            ArrayNode tools,
            Consumer<String> textDelta
    ) throws IOException, InterruptedException {
        return createResponse(instructions, input, tools, textDelta, StreamEventListener.noop());
    }

    public ResponseResult createResponse(
            String instructions,
            ArrayNode input,
            ArrayNode tools,
            Consumer<String> textDelta,
            StreamEventListener eventListener
    ) throws IOException, InterruptedException {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IOException("OPENAI_API_KEY is not set");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("instructions", instructions);
        body.set("input", input);
        body.set("tools", tools);
        body.put("tool_choice", "auto");
        body.put("parallel_tool_calls", false);
        body.put("store", false);
        body.put("stream", true);
        body.putArray("include").add("reasoning.encrypted_content");

        HttpRequest request = HttpRequest.newBuilder(responsesUri())
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Responses API returned HTTP " + response.statusCode() + ": " + error);
        }
        return parseStream(response.body(), textDelta, eventListener);
    }

    private ResponseResult parseStream(
            InputStream stream,
            Consumer<String> textDelta,
            StreamEventListener eventListener
    ) throws IOException {
        List<JsonNode> outputItems = new ArrayList<>();
        String responseId = null;
        boolean completed = false;
        Usage usage = Usage.empty();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        JsonNode event = mapper.readTree(data.toString());
                        String type = event.path("type").asText();
                        switch (type) {
                            case "response.created" ->
                                    eventListener.onEvent(new StreamEvent.ResponseStarted(
                                            event.path("response").path("id").asText(null)
                                    ));
                            case "response.output_text.delta" -> {
                                String delta = event.path("delta").asText();
                                textDelta.accept(delta);
                                eventListener.onEvent(new StreamEvent.OutputTextDelta(delta));
                            }
                            case "response.reasoning_summary_part.added" ->
                                    eventListener.onEvent(new StreamEvent.ReasoningSummaryPartAdded(
                                            event.path("summary_index").asInt(0)
                                    ));
                            case "response.reasoning_summary_text.delta" ->
                                    eventListener.onEvent(new StreamEvent.ReasoningSummaryDelta(
                                            event.path("delta").asText(),
                                            event.path("summary_index").asInt(0)
                                    ));
                            case "response.reasoning_summary_text.done" ->
                                    eventListener.onEvent(new StreamEvent.ReasoningSummaryDone(
                                            event.path("text").asText(),
                                            event.path("summary_index").asInt(0)
                                    ));
                            case "response.output_item.done" -> {
                                JsonNode item = event.get("item");
                                if (item != null && !item.isNull()) {
                                    outputItems.add(item.deepCopy());
                                    eventListener.onEvent(new StreamEvent.OutputItemDone(item.deepCopy()));
                                }
                            }
                            case "response.completed" -> {
                                completed = true;
                                responseId = event.path("response").path("id").asText(null);
                                usage = usage(event.path("response").path("usage"));
                                eventListener.onEvent(new StreamEvent.ResponseCompleted(responseId, usage));
                            }
                            case "response.failed", "response.incomplete", "error" -> {
                                String message = streamErrorMessage(event);
                                eventListener.onEvent(new StreamEvent.StreamError(type, message));
                                throw new IOException("Responses stream failed: " + message);
                            }
                            default -> {
                            }
                        }
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    String fragment = line.substring(5).stripLeading();
                    if (!"[DONE]".equals(fragment)) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(fragment);
                    }
                }
            }
        }

        if (!completed) {
            throw new IOException("Responses stream closed before response.completed");
        }
        return new ResponseResult(responseId, List.copyOf(outputItems), usage);
    }

    private static Usage usage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Usage.empty();
        }
        return new Usage(
                node.path("input_tokens").asLong(0),
                node.path("input_tokens_details").path("cached_tokens").asLong(0),
                node.path("output_tokens").asLong(0),
                node.path("output_tokens_details").path("reasoning_tokens").asLong(0),
                node.path("total_tokens").asLong(0)
        );
    }

    private static String streamErrorMessage(JsonNode event) {
        JsonNode error = event.path("response").path("error");
        if (error.isMissingNode() || error.isNull()) {
            error = event.path("error");
        }
        String message = error.path("message").asText();
        return message.isBlank() ? event.toString() : message;
    }

    private URI responsesUri() {
        String base = config.baseUrl().toString();
        return URI.create(base.endsWith("/responses") ? base : base + "/responses");
    }

    public sealed interface StreamEvent {
        record ResponseStarted(String responseId) implements StreamEvent {
        }

        record OutputTextDelta(String delta) implements StreamEvent {
        }

        record ReasoningSummaryPartAdded(int summaryIndex) implements StreamEvent {
        }

        record ReasoningSummaryDelta(String delta, int summaryIndex) implements StreamEvent {
        }

        record ReasoningSummaryDone(String text, int summaryIndex) implements StreamEvent {
        }

        record OutputItemDone(JsonNode item) implements StreamEvent {
        }

        record ResponseCompleted(String responseId, Usage usage) implements StreamEvent {
        }

        record StreamError(String type, String message) implements StreamEvent {
        }
    }

    public record Usage(
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long reasoningOutputTokens,
            long totalTokens
    ) {
        public static Usage empty() {
            return new Usage(0, 0, 0, 0, 0);
        }
    }

    public record ResponseResult(String responseId, List<JsonNode> outputItems, Usage usage) {
    }
}
