package io.github.codexjava.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.codexjava.agent.AgentExecutionContext;
import io.github.codexjava.agent.AgentStatus;
import io.github.codexjava.agent.MultiAgentManager;
import io.github.codexjava.config.CodexConfig;

import java.util.List;

public final class MultiAgentTools {
    private MultiAgentTools() {
    }

    public static List<CodexTool> create(ObjectMapper mapper, CodexConfig.MultiAgentConfig config) {
        String namespace = config.toolNamespace();
        return List.of(
                new SpawnAgentTool(mapper, namespace),
                new SendMessageTool(mapper, namespace, false),
                new SendMessageTool(mapper, namespace, true),
                new WaitAgentTool(mapper, namespace, config),
                new InterruptAgentTool(mapper, namespace),
                new ListAgentsTool(mapper, namespace)
        );
    }

    private abstract static class MultiAgentTool implements CodexTool {
        protected final ObjectMapper mapper;
        private final String namespace;

        private MultiAgentTool(ObjectMapper mapper, String namespace) {
            this.mapper = mapper;
            this.namespace = namespace;
        }

        @Override
        public String namespace() {
            return namespace;
        }

        protected MultiAgentManager manager(AgentExecutionContext context) {
            if (context.multiAgentManager() == null) {
                throw new IllegalStateException("collab manager unavailable");
            }
            return context.multiAgentManager();
        }

        protected ObjectNode functionSpec(String description, ObjectNode properties, String... required) {
            ObjectNode parameters = mapper.createObjectNode();
            parameters.put("type", "object");
            parameters.set("properties", properties);
            ArrayNode requiredFields = parameters.putArray("required");
            for (String field : required) {
                requiredFields.add(field);
            }
            parameters.put("additionalProperties", false);

            ObjectNode spec = mapper.createObjectNode();
            spec.put("type", "function");
            spec.put("name", name());
            spec.put("description", description);
            spec.put("strict", false);
            spec.set("parameters", parameters);
            return spec;
        }

        protected ObjectNode stringProperty(String description) {
            return mapper.createObjectNode().put("type", "string").put("description", description);
        }

        protected ObjectNode integerProperty(String description) {
            return mapper.createObjectNode().put("type", "integer").put("description", description);
        }

        protected String requiredText(JsonNode arguments, String name) {
            String value = arguments.path(name).asText();
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }

        protected ToolResult jsonResult(JsonNode value) throws Exception {
            return ToolResult.success(mapper.writeValueAsString(value));
        }
    }

    private static final class SpawnAgentTool extends MultiAgentTool {
        private SpawnAgentTool(ObjectMapper mapper, String namespace) {
            super(mapper, namespace);
        }

        @Override
        public String name() {
            return "spawn_agent";
        }

        @Override
        public ObjectNode specification() {
            ObjectNode properties = mapper.createObjectNode();
            properties.set("task_name", stringProperty(
                    "Task name for the new agent. Use lowercase letters, digits, and underscores."
            ));
            properties.set("message", stringProperty("Initial plain-text task for the new agent."));
            properties.set("fork_turns", stringProperty(
                    "Optional number of turns to fork. Defaults to `all`. Use `none`, `all`, "
                            + "or a positive integer string such as `3`."
            ));
            return functionSpec(
                    "Spawns an agent to work on a concrete, bounded task. The child can use the "
                            + "same tools and spawn its own sub-agents. Its final answer is delivered "
                            + "to the parent automatically.",
                    properties,
                    "task_name",
                    "message"
            );
        }

        @Override
        public ToolResult execute(JsonNode arguments, AgentExecutionContext context) throws Exception {
            MultiAgentManager.SpawnResult result = manager(context).spawn(
                    context,
                    requiredText(arguments, "task_name"),
                    requiredText(arguments, "message"),
                    arguments.path("fork_turns").asText(null),
                    arguments.path("model").asText(null),
                    arguments.path("reasoning_effort").asText(null),
                    arguments.path("agent_type").asText(null),
                    arguments.has("fork_context") ? arguments.path("fork_context").asBoolean() : null
            );
            ObjectNode output = mapper.createObjectNode();
            output.put("task_name", result.taskName());
            if (result.nickname() == null) {
                output.putNull("nickname");
            } else {
                output.put("nickname", result.nickname());
            }
            return jsonResult(output);
        }
    }

    private static final class SendMessageTool extends MultiAgentTool {
        private final boolean triggerTurn;

        private SendMessageTool(ObjectMapper mapper, String namespace, boolean triggerTurn) {
            super(mapper, namespace);
            this.triggerTurn = triggerTurn;
        }

        @Override
        public String name() {
            return triggerTurn ? "followup_task" : "send_message";
        }

        @Override
        public ObjectNode specification() {
            ObjectNode properties = mapper.createObjectNode();
            properties.set("target", stringProperty(
                    triggerTurn
                            ? "Agent id or canonical task name to send a follow-up task to."
                            : "Relative or canonical task name to message."
            ));
            properties.set("message", stringProperty("Message text to send to the target agent."));
            return functionSpec(
                    triggerTurn
                            ? "Send a follow-up task and trigger a turn if the target is idle."
                            : "Send a message to an existing agent without triggering a new turn.",
                    properties,
                    "target",
                    "message"
            );
        }

        @Override
        public ToolResult execute(JsonNode arguments, AgentExecutionContext context) {
            String target = requiredText(arguments, "target");
            String message = requiredText(arguments, "message");
            if (triggerTurn) {
                manager(context).followupTask(context, target, message);
            } else {
                manager(context).sendMessage(context, target, message);
            }
            return ToolResult.success("");
        }
    }

    private static final class WaitAgentTool extends MultiAgentTool {
        private final CodexConfig.MultiAgentConfig config;

        private WaitAgentTool(
                ObjectMapper mapper,
                String namespace,
                CodexConfig.MultiAgentConfig config
        ) {
            super(mapper, namespace);
            this.config = config;
        }

        @Override
        public String name() {
            return "wait_agent";
        }

        @Override
        public ObjectNode specification() {
            ObjectNode properties = mapper.createObjectNode();
            properties.set("timeout_ms", integerProperty(
                    "Wait timeout in milliseconds. Defaults to " + config.defaultWaitTimeoutMillis()
                            + "; effective range is " + config.minWaitTimeoutMillis()
                            + "-" + config.maxWaitTimeoutMillis() + "."
            ));
            return functionSpec(
                    "Wait for a mailbox update from any live agent. The result contains a summary "
                            + "rather than the message body.",
                    properties
            );
        }

        @Override
        public ToolResult execute(JsonNode arguments, AgentExecutionContext context) throws Exception {
            Long timeout = arguments.has("timeout_ms") ? arguments.path("timeout_ms").asLong() : null;
            MultiAgentManager.WaitResult result = manager(context).waitForActivity(context, timeout);
            ObjectNode output = mapper.createObjectNode();
            output.put("message", result.message());
            output.put("timed_out", result.timedOut());
            return jsonResult(output);
        }
    }

    private static final class InterruptAgentTool extends MultiAgentTool {
        private InterruptAgentTool(ObjectMapper mapper, String namespace) {
            super(mapper, namespace);
        }

        @Override
        public String name() {
            return "interrupt_agent";
        }

        @Override
        public ObjectNode specification() {
            ObjectNode properties = mapper.createObjectNode();
            properties.set("target", stringProperty("Agent id or canonical task name to interrupt."));
            return functionSpec(
                    "Interrupt an agent's current turn and return its previous status.",
                    properties,
                    "target"
            );
        }

        @Override
        public ToolResult execute(JsonNode arguments, AgentExecutionContext context) throws Exception {
            AgentStatus status = manager(context).interrupt(context, requiredText(arguments, "target"));
            ObjectNode output = mapper.createObjectNode();
            output.set("previous_status", status.toJson(mapper));
            return jsonResult(output);
        }
    }

    private static final class ListAgentsTool extends MultiAgentTool {
        private ListAgentsTool(ObjectMapper mapper, String namespace) {
            super(mapper, namespace);
        }

        @Override
        public String name() {
            return "list_agents";
        }

        @Override
        public ObjectNode specification() {
            ObjectNode properties = mapper.createObjectNode();
            properties.set(
                    "path_prefix",
                    stringProperty("Task-path prefix without a trailing slash. Omit to list all live agents.")
            );
            return functionSpec(
                    "List live agents in the current root thread tree.",
                    properties
            );
        }

        @Override
        public ToolResult execute(JsonNode arguments, AgentExecutionContext context) throws Exception {
            ObjectNode output = mapper.createObjectNode();
            ArrayNode agents = output.putArray("agents");
            for (MultiAgentManager.ListedAgent agent : manager(context).listAgents(
                    context,
                    arguments.path("path_prefix").asText(null)
            )) {
                ObjectNode listed = agents.addObject();
                listed.put("agent_name", agent.agentName());
                listed.set("agent_status", agent.agentStatus().toJson(mapper));
            }
            return jsonResult(output);
        }
    }
}
