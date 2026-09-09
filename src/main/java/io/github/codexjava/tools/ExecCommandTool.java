package io.github.codexjava.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.codexjava.agent.AgentExecutionContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ExecCommandTool implements CodexTool {
    private final ObjectMapper mapper;
    private final WorkspacePolicy workspacePolicy;
    private final ApprovalGate approvalGate;
    private final int maxOutputCharacters;

    public ExecCommandTool(
            ObjectMapper mapper,
            WorkspacePolicy workspacePolicy,
            ApprovalGate approvalGate,
            int maxOutputCharacters
    ) {
        this.mapper = mapper;
        this.workspacePolicy = workspacePolicy;
        this.approvalGate = approvalGate;
        this.maxOutputCharacters = maxOutputCharacters;
    }

    @Override
    public String name() {
        return "exec_command";
    }

    @Override
    public ObjectNode specification() {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("cmd", stringSchema("Shell command to execute."));
        properties.set("workdir", stringSchema("Working directory. Defaults to the current workspace."));
        properties.set("shell", stringSchema("Shell binary. Defaults to the SHELL environment variable."));
        properties.set("yield_time_ms", integerSchema("Maximum execution wait in milliseconds."));
        properties.set("max_output_tokens", integerSchema("Approximate output token limit."));
        properties.set("sandbox_permissions", stringSchema("Set to require_escalated to request approval."));
        properties.set("justification", stringSchema("Short approval explanation."));

        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        parameters.putArray("required").add("cmd");
        parameters.put("additionalProperties", false);

        ObjectNode spec = mapper.createObjectNode();
        spec.put("type", "function");
        spec.put("name", name());
        spec.put("description", "Runs a shell command and returns exit status and bounded combined output.");
        spec.put("strict", false);
        spec.set("parameters", parameters);
        return spec;
    }

    @Override
    public ToolResult execute(JsonNode arguments, AgentExecutionContext context) throws Exception {
        String command = requiredText(arguments, "cmd");
        Path workdir = workspacePolicy.resolveWorkingDirectory(arguments.path("workdir").asText(null));
        boolean escalated = "require_escalated".equals(arguments.path("sandbox_permissions").asText());
        boolean requiresApproval = escalated || workspacePolicy.commandNeedsApproval(command);
        String justification = arguments.path("justification").asText(command);
        if (!approvalGate.approve(justification, requiresApproval)) {
            return ToolResult.failure("Command rejected by approval policy");
        }

        String shell = arguments.path("shell").asText(System.getenv().getOrDefault("SHELL", "/bin/bash"));
        long timeoutMillis = clamp(arguments.path("yield_time_ms").asLong(30_000), 250, 300_000);
        Process process = new ProcessBuilder(List.of(shell, "-lc", command))
                .directory(workdir.toFile())
                .redirectErrorStream(true)
                .start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(
                () -> readOutput(process, output, context),
                "codex-java-command-output"
        );
        reader.setDaemon(true);
        reader.start();
        boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(Duration.ofSeconds(2).toMillis());
            return ToolResult.failure(formatResult(-1, true, output));
        }
        reader.join();
        int exitCode = process.exitValue();
        return new ToolResult(formatResult(exitCode, false, output), exitCode == 0);
    }

    private void readOutput(
            Process process,
            StringBuilder output,
            AgentExecutionContext context
    ) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    if (output.length() < maxOutputCharacters) {
                        int remaining = maxOutputCharacters - output.length();
                        String delta = line.substring(0, Math.min(line.length(), remaining)) + '\n';
                        output.append(delta);
                        emitOutputDelta(context, delta);
                    }
                }
            }
        } catch (Exception error) {
            synchronized (output) {
                output.append("\n[output read failed: ").append(error.getMessage()).append(']');
            }
        }
    }

    private void emitOutputDelta(AgentExecutionContext context, String delta) {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "item.updated");
        event.put("timestamp", Instant.now().toString());
        event.put("thread_id", context.threadId());
        event.put("agent_path", context.agentPath().toString());
        ObjectNode item = event.putObject("item");
        item.put("id", context.toolCallId());
        item.put("type", "command_execution");
        item.put("delta", delta);
        context.eventListener().onEvent(event);
    }

    private String formatResult(int exitCode, boolean timedOut, StringBuilder output) {
        List<String> lines = new ArrayList<>();
        lines.add("Process exited with code " + exitCode);
        if (timedOut) {
            lines.add("Process timed out and was terminated");
        }
        lines.add("Final output:");
        synchronized (output) {
            lines.add(output.toString());
        }
        return String.join("\n", lines);
    }

    private ObjectNode stringSchema(String description) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private ObjectNode integerSchema(String description) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    private static String requiredText(JsonNode node, String name) {
        String value = node.path(name).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
