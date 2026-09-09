package io.github.codexjava.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.codexjava.agent.AgentEventListener;
import io.github.codexjava.agent.SessionStore;
import io.github.codexjava.config.CodexConfig;

import java.io.PrintStream;
import java.time.Instant;

final class CliEventRenderer implements AgentEventListener {
    private final ObjectMapper mapper;
    private final PrintStream output;
    private final boolean json;
    private boolean reasoningLineOpen;

    private CliEventRenderer(ObjectMapper mapper, PrintStream output, boolean json) {
        this.mapper = mapper;
        this.output = output;
        this.json = json;
    }

    static CliEventRenderer human(PrintStream error) {
        return new CliEventRenderer(new ObjectMapper(), error, false);
    }

    static CliEventRenderer json(ObjectMapper mapper, PrintStream output) {
        return new CliEventRenderer(mapper, output, true);
    }

    void threadStarted(SessionStore.Session session, CodexConfig config) {
        if (!json) {
            return;
        }
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "thread.started");
        event.put("timestamp", Instant.now().toString());
        event.put("thread_id", session.id());
        event.put("model", config.model());
        event.put("cwd", config.workingDirectory().toString());
        onEvent(event);
    }

    @Override
    public synchronized void onEvent(ObjectNode event) {
        if (json) {
            try {
                output.println(mapper.writeValueAsString(event));
                output.flush();
            } catch (Exception error) {
                output.printf(
                        "{\"type\":\"error\",\"message\":%s}%n",
                        quote("failed to serialize CLI event: " + error.getMessage())
                );
                output.flush();
            }
            return;
        }
        renderHuman(event);
    }

    private void renderHuman(ObjectNode event) {
        String eventType = event.path("type").asText();
        ObjectNode item = event.path("item") instanceof ObjectNode object ? object : null;
        if ("item.started".equals(eventType) && item != null) {
            switch (item.path("type").asText()) {
                case "reasoning" -> {
                    closeReasoningLine();
                    output.print("thinking: ");
                    reasoningLineOpen = true;
                }
                case "command_execution" ->
                        output.println("exec: " + argument(item.path("arguments"), "cmd"));
                case "file_change" -> output.println("apply_patch: started");
                case "collab_tool_call" ->
                        output.println("collaboration: " + item.path("tool").asText() + " started");
                case "tool_call" -> output.println("tool: " + item.path("tool").asText() + " started");
                default -> {
                }
            }
        } else if ("item.updated".equals(eventType) && item != null
                && "reasoning".equals(item.path("type").asText())) {
            output.print(item.path("delta").asText());
            output.flush();
            reasoningLineOpen = true;
        } else if ("item.updated".equals(eventType) && item != null
                && "command_execution".equals(item.path("type").asText())) {
            output.print(item.path("delta").asText());
        } else if ("item.completed".equals(eventType) && item != null) {
            if ("reasoning".equals(item.path("type").asText())) {
                closeReasoningLine();
            } else if (!"agent_message".equals(item.path("type").asText())) {
                String tool = item.path("tool").asText(item.path("type").asText("tool"));
                output.printf("%s: %s%n", tool, item.path("status").asText("completed"));
            }
        } else if ("turn.failed".equals(eventType) || "error".equals(eventType)) {
            closeReasoningLine();
            output.println("error: " + event.path("message").asText());
        }
        output.flush();
    }

    private void closeReasoningLine() {
        if (reasoningLineOpen) {
            output.println();
            reasoningLineOpen = false;
        }
    }

    private String argument(com.fasterxml.jackson.databind.JsonNode arguments, String name) {
        if (arguments.isObject()) {
            return arguments.path(name).asText(arguments.toString());
        }
        if (arguments.isTextual()) {
            try {
                return mapper.readTree(arguments.asText()).path(name).asText(arguments.asText());
            } catch (Exception ignored) {
                return arguments.asText();
            }
        }
        return arguments.toString();
    }

    private String quote(String text) {
        try {
            return mapper.writeValueAsString(text);
        } catch (Exception ignored) {
            return "\"serialization error\"";
        }
    }
}
