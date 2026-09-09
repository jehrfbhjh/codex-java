package io.github.codexjava.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.codexjava.agent.AgentExecutionContext;

public interface CodexTool {
    String name();

    default String namespace() {
        return null;
    }

    ObjectNode specification();

    ToolResult execute(JsonNode arguments, AgentExecutionContext context) throws Exception;

    record ToolResult(String output, boolean success) {
        public static ToolResult success(String output) {
            return new ToolResult(output, true);
        }

        public static ToolResult failure(String output) {
            return new ToolResult(output, false);
        }
    }
}
