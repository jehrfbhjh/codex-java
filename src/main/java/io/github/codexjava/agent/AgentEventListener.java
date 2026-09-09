package io.github.codexjava.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;

@FunctionalInterface
public interface AgentEventListener {
    void onEvent(ObjectNode event);

    static AgentEventListener noop() {
        return ignored -> {
        };
    }
}
