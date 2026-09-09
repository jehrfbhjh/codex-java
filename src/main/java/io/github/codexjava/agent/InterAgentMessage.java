package io.github.codexjava.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.UUID;

public record InterAgentMessage(
        String id,
        AgentPath author,
        AgentPath recipient,
        String content,
        boolean triggerTurn
) {
    public InterAgentMessage {
        Objects.requireNonNull(id);
        Objects.requireNonNull(author);
        Objects.requireNonNull(recipient);
        Objects.requireNonNull(content);
    }

    public static InterAgentMessage newTask(AgentPath author, AgentPath recipient, String payload) {
        return create(author, recipient, payload, true, "NEW_TASK");
    }

    public static InterAgentMessage message(AgentPath author, AgentPath recipient, String payload) {
        return create(author, recipient, payload, false, "MESSAGE");
    }

    public static InterAgentMessage completion(
            AgentPath author,
            AgentPath recipient,
            AgentStatus status
    ) {
        String payload;
        if (status instanceof AgentStatus.Completed completed) {
            payload = completed.message() == null ? "" : completed.message();
        } else if (status instanceof AgentStatus.Errored errored) {
            payload = "Agent errored: " + truncate(errored.message(), 3600)
                    + "\n\nThis agent's turn failed. If you still need this agent, use the available "
                    + "collaboration tools to give it another task.";
        } else if (status instanceof AgentStatus.Shutdown) {
            payload = "Agent shut down.";
        } else if (status instanceof AgentStatus.NotFound) {
            payload = "Agent was not found.";
        } else {
            throw new IllegalArgumentException("status is not final: " + status);
        }
        return createAllowEmpty(author, recipient, payload, false, "FINAL_ANSWER");
    }

    public ObjectNode toModelItem(ObjectMapper mapper) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "message");
        item.put("role", "user");
        ArrayNode contentItems = item.putArray("content");
        contentItems.addObject()
                .put("type", "input_text")
                .put("text", content);
        return item;
    }

    private static InterAgentMessage create(
            AgentPath author,
            AgentPath recipient,
            String payload,
            boolean triggerTurn,
            String type
    ) {
        if (payload == null || payload.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty message can't be sent to an agent");
        }
        return createAllowEmpty(author, recipient, payload, triggerTurn, type);
    }

    private static InterAgentMessage createAllowEmpty(
            AgentPath author,
            AgentPath recipient,
            String payload,
            boolean triggerTurn,
            String type
    ) {
        String rendered = "Message Type: " + type
                + "\nTask name: " + recipient
                + "\nSender: " + author
                + "\nPayload:\n" + payload;
        return new InterAgentMessage(UUID.randomUUID().toString(), author, recipient, rendered, triggerTurn);
    }

    private static String truncate(String value, int maxCharacters) {
        return value.length() <= maxCharacters ? value : value.substring(0, maxCharacters) + "…";
    }
}
