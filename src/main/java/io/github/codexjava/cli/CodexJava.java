package io.github.codexjava.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codexjava.agent.CodexAgent;
import io.github.codexjava.agent.AgentExecutionContext;
import io.github.codexjava.agent.SessionStore;
import io.github.codexjava.config.ConfigLoader;
import io.github.codexjava.web.CodexWebServer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;

@Command(
        name = "codex-java",
        version = "codex-java 0.1.0",
        mixinStandardHelpOptions = true,
        description = "Java implementation of the core Codex coding-agent loop.",
        subcommands = {
                CodexJava.ExecCommand.class,
                CodexJava.ResumeCommand.class,
                CodexJava.SessionsCommand.class,
                CodexJava.WebCommand.class
        }
)
public final class CodexJava implements Callable<Integer> {
    @Mixin
    private CommonOptions options;

    @Parameters(index = "0", arity = "0..1", description = "Initial prompt.")
    private String prompt;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new CodexJava()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        try (CodexRuntime runtime = CodexRuntime.create(options.overrides())) {
            SessionStore.Session session = runtime.agent().newSession();
            AgentExecutionContext context = runtime.registerRoot(session);
            if (prompt != null && !prompt.isBlank()) {
                runTurn(runtime.agent(), context, prompt);
            }

            System.out.printf(
                    "Codex Java interactive session %s (%s)%nType /exit to quit.%n",
                    session.id(),
                    runtime.config().model()
            );
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                while (true) {
                    System.out.print("> ");
                    System.out.flush();
                    String line = reader.readLine();
                    if (line == null || "/exit".equals(line.strip()) || "/quit".equals(line.strip())) {
                        return 0;
                    }
                    if (!line.isBlank()) {
                        runTurn(runtime.agent(), context, line);
                    }
                }
            }
        }
    }

    private static void runTurn(CodexAgent agent, AgentExecutionContext context, String prompt) throws Exception {
        CodexAgent.TurnResult result = agent.runTurn(
                context,
                userMessage(prompt),
                System.out::print,
                CliEventRenderer.human(System.err)
        );
        if (!result.text().endsWith(System.lineSeparator())) {
            System.out.println();
        }
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode userMessage(String prompt) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode message = mapper.createObjectNode();
        message.put("type", "message");
        message.put("role", "user");
        message.putArray("content").addObject().put("type", "input_text").put("text", prompt);
        return message;
    }

    static final class CommonOptions {
        @Option(names = {"-m", "--model"}, description = "Model name.")
        private String model;

        @Option(names = "--base-url", description = "OpenAI-compatible API base URL.")
        private String baseUrl;

        @Option(names = "--api-key", description = "API key. Prefer OPENAI_API_KEY.")
        private String apiKey;

        @Option(names = {"-C", "--cwd"}, description = "Working directory.")
        private Path workingDirectory;

        @Option(names = "--codex-home", description = "Config and session directory.")
        private Path codexHome;

        @Option(names = {"-a", "--ask-for-approval"}, description = "on-request or never.")
        private String approvalPolicy;

        @Option(names = {"-s", "--sandbox"}, description = "read-only, workspace-write, or danger-full-access.")
        private String sandboxMode;

        @Option(names = "--max-turns", description = "Maximum model/tool loop iterations.", defaultValue = "32")
        private Integer maxTurns;

        ConfigLoader.Overrides overrides() {
            return new ConfigLoader.Overrides(
                    model,
                    baseUrl,
                    apiKey,
                    codexHome,
                    workingDirectory,
                    approvalPolicy,
                    sandboxMode,
                    maxTurns
            );
        }
    }

    @Command(name = "exec", aliases = "e", mixinStandardHelpOptions = true, description = "Run non-interactively.")
    static final class ExecCommand implements Callable<Integer> {
        @Mixin
        private CommonOptions options;

        @Parameters(index = "0", arity = "0..1", description = "Prompt. Reads stdin when omitted.")
        private String prompt;

        @Option(names = "--json", description = "Emit newline-delimited JSON events to stdout.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            String effectivePrompt = prompt;
            if (effectivePrompt == null) {
                effectivePrompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (effectivePrompt.isBlank()) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "A prompt argument or stdin input is required"
                );
            }
            try (CodexRuntime runtime = CodexRuntime.create(options.overrides())) {
                SessionStore.Session session = runtime.agent().newSession();
                AgentExecutionContext context = runtime.registerRoot(session);
                CliEventRenderer renderer = json
                        ? CliEventRenderer.json(new ObjectMapper(), System.out)
                        : CliEventRenderer.human(System.err);
                renderer.threadStarted(session, runtime.config());
                CodexAgent.TurnResult result = runtime.agent().runTurn(
                        context,
                        userMessage(effectivePrompt),
                        json ? ignored -> {
                        } : System.out::print,
                        renderer
                );
                if (!json && !result.text().endsWith(System.lineSeparator())) {
                    System.out.println();
                }
            }
            return 0;
        }
    }

    @Command(name = "resume", mixinStandardHelpOptions = true, description = "Resume the latest Java session.")
    static final class ResumeCommand implements Callable<Integer> {
        @Mixin
        private CommonOptions options;

        @Option(names = "--last", description = "Resume the most recently modified session.", defaultValue = "true")
        private boolean last;

        @Parameters(index = "0", arity = "0..1", description = "Optional prompt.")
        private String prompt;

        @Override
        public Integer call() throws Exception {
            if (!last) {
                throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Only --last is implemented in this release"
                );
            }
            try (CodexRuntime runtime = CodexRuntime.create(options.overrides())) {
                SessionStore.Session session = runtime.sessionStore().resumeLast();
                AgentExecutionContext context = runtime.registerRoot(session);
                if (prompt != null && !prompt.isBlank()) {
                    runTurn(runtime.agent(), context, prompt);
                    return 0;
                }
                System.out.println("Resumed session " + session.id() + ". Provide a prompt argument to continue it.");
            }
            return 0;
        }
    }

    @Command(name = "sessions", mixinStandardHelpOptions = true, description = "List saved Java sessions.")
    static final class SessionsCommand implements Callable<Integer> {
        @Mixin
        private CommonOptions options;

        @Option(names = "--limit", defaultValue = "20")
        private int limit;

        @Override
        public Integer call() throws Exception {
            try (CodexRuntime runtime = CodexRuntime.create(options.overrides())) {
                for (SessionStore.SessionSummary summary : runtime.sessionStore().list(limit)) {
                    System.out.printf(
                            "%s  %s  %s  %s%n",
                            summary.timestamp(),
                            summary.id(),
                            summary.model(),
                            summary.cwd()
                    );
                }
            }
            return 0;
        }
    }

    @Command(name = "web", mixinStandardHelpOptions = true, description = "Start the local Codex web interface.")
    static final class WebCommand implements Callable<Integer> {
        @Mixin
        private CommonOptions options;

        @Option(names = "--host", defaultValue = "127.0.0.1", description = "HTTP bind host.")
        private String host;

        @Option(names = "--port", defaultValue = "8765", description = "HTTP bind port.")
        private int port;

        @Override
        public Integer call() throws Exception {
            try (CodexRuntime runtime = CodexRuntime.create(options.overrides());
                 CodexWebServer webServer = new CodexWebServer(runtime, host, port)) {
                webServer.start();
                System.out.printf(
                        "Codex Java web is running at http://%s:%d%nWorkspace: %s%nPress Ctrl+C to stop.%n",
                        host,
                        webServer.port(),
                        runtime.config().workingDirectory()
                );
                new CountDownLatch(1).await();
            }
            return 0;
        }
    }
}
