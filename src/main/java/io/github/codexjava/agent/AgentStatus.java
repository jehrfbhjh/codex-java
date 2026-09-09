package io.github.codexjava.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

public sealed interface AgentStatus permits
        AgentStatus.PendingInit,
        AgentStatus.Running,
        AgentStatus.Interrupted,
        AgentStatus.Completed,
        AgentStatus.Errored,
        AgentStatus.Shutdown,
        AgentStatus.NotFound {

    default boolean isFinal() {
        return this instanceof Completed
                || this instanceof Errored
                || this instanceof Shutdown
                || this instanceof NotFound;
    }

    JsonNode toJson(ObjectMapper mapper);

    record PendingInit() implements AgentStatus {
        @Override
        public JsonNode toJson(ObjectMapper mapper) {
            return mapper.getNodeFactory().textNode("pending_init");
        }
    }

    record Running() implements AgentStatus {
        @Override
        public JsonNode toJson(ObjectMapper mapper) {
            return mapper.getNodeFactory().textNode("running");
        }
    }

    record Interrupted() implements AgentStatus {
        @Override
        public JsonNode toJson(ObjectMapper mapper) {
            return mapper.getNodeFactory().textNode("interrupted");
        }
    }

    record Completed(String message) implements AgentStatus {
        @Override
        public JsonNode toJson(ObjectMapper mapper) {
            ObjectNode value = mapper.createObjectNode();
            if (message == null) {
                value.putNull("completed");
            } else {
                value.put("completed", message);
            }
            return value;
        }
    }

    record Errored(String message) implements AgentStatus {
        public Errored {
            Objects.requireNonNull(message);
        }

        @Override
        public JsonNode toJson(ObjectMapper mapper) {
            return mapper.createObjectNode().put("errored", message);
        }
    }

    record Shutdown() implements AgentStatus {
        @Override
        public JsonNode toJson(ObjectMapper mapper) {
            return mapper.getNodeFactory().textNode("shutdown");
        }
    }

    record NotFound() implements AgentStatus {
        @Override
        public JsonNode toJson(ObjectMapper mapper) {
            return mapper.getNodeFactory().textNode("not_found");
        }
    }
}
