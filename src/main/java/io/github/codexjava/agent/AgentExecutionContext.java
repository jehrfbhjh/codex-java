package io.github.codexjava.agent;

import java.util.Objects;

public record AgentExecutionContext(
        String threadId,
        AgentPath agentPath,
        SessionStore.Session session,
        MultiAgentManager multiAgentManager,
        String toolCallId,
        AgentEventListener eventListener
) {
    public AgentExecutionContext {
        Objects.requireNonNull(threadId);
        Objects.requireNonNull(agentPath);
        Objects.requireNonNull(session);
        eventListener = eventListener == null ? AgentEventListener.noop() : eventListener;
    }

    public AgentExecutionContext(
            String threadId,
            AgentPath agentPath,
            SessionStore.Session session,
            MultiAgentManager multiAgentManager,
            String toolCallId
    ) {
        this(threadId, agentPath, session, multiAgentManager, toolCallId, AgentEventListener.noop());
    }

    public static AgentExecutionContext root(SessionStore.Session session) {
        return new AgentExecutionContext(session.id(), AgentPath.root(), session, null, null);
    }

    public AgentExecutionContext forToolCall(String callId) {
        return new AgentExecutionContext(threadId, agentPath, session, multiAgentManager, callId, eventListener);
    }

    public AgentExecutionContext withEventListener(AgentEventListener listener) {
        return new AgentExecutionContext(threadId, agentPath, session, multiAgentManager, toolCallId, listener);
    }
}
