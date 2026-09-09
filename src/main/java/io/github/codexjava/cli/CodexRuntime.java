package io.github.codexjava.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codexjava.agent.AgentExecutionContext;
import io.github.codexjava.agent.CodexAgent;
import io.github.codexjava.agent.InstructionLoader;
import io.github.codexjava.agent.MultiAgentManager;
import io.github.codexjava.agent.SessionStore;
import io.github.codexjava.api.ResponsesApiClient;
import io.github.codexjava.config.CodexConfig;
import io.github.codexjava.config.ConfigLoader;
import io.github.codexjava.tools.ApplyPatchTool;
import io.github.codexjava.tools.ApprovalGate;
import io.github.codexjava.tools.ExecCommandTool;
import io.github.codexjava.tools.MultiAgentTools;
import io.github.codexjava.tools.WorkspacePolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

record CodexRuntime(
        CodexConfig config,
        CodexAgent agent,
        SessionStore sessionStore,
        MultiAgentManager multiAgentManager
) implements AutoCloseable {
    static CodexRuntime create(ConfigLoader.Overrides overrides) throws IOException {
        CodexConfig config = ConfigLoader.load(overrides);
        ObjectMapper mapper = new ObjectMapper();
        WorkspacePolicy workspacePolicy = new WorkspacePolicy(config.workingDirectory(), config.sandboxMode());
        ApprovalGate approvalGate = new ApprovalGate(config.approvalPolicy());
        SessionStore store = new SessionStore(mapper, config.codexHome());
        MultiAgentManager.AgentFactory agentFactory = (agentConfig, manager) ->
                createAgent(agentConfig, mapper, store, workspacePolicy, approvalGate, manager);
        MultiAgentManager manager = new MultiAgentManager(mapper, store, config, agentFactory);
        CodexAgent agent = createAgent(config, mapper, store, workspacePolicy, approvalGate, manager);
        return new CodexRuntime(config, agent, store, manager);
    }

    private static CodexAgent createAgent(
            CodexConfig config,
            ObjectMapper mapper,
            SessionStore store,
            WorkspacePolicy workspacePolicy,
            ApprovalGate approvalGate,
            MultiAgentManager manager
    ) throws IOException {
        List<io.github.codexjava.tools.CodexTool> tools = new ArrayList<>();
        tools.add(new ExecCommandTool(
                mapper,
                workspacePolicy,
                approvalGate,
                config.maxOutputCharacters()
        ));
        tools.add(new ApplyPatchTool(mapper, workspacePolicy));
        if (config.multiAgent().enabled()) {
            tools.addAll(MultiAgentTools.create(mapper, config.multiAgent()));
        }
        return new CodexAgent(
                config,
                mapper,
                new ResponsesApiClient(config, mapper),
                store,
                new InstructionLoader().load(config.workingDirectory()),
                tools
        );
    }

    AgentExecutionContext registerRoot(SessionStore.Session session) {
        return multiAgentManager.registerRoot(session);
    }

    @Override
    public void close() {
        multiAgentManager.close();
    }
}
