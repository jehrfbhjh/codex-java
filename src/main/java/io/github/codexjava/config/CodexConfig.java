package io.github.codexjava.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record CodexConfig(
        String model,
        URI baseUrl,
        String apiKey,
        Path codexHome,
        Path workingDirectory,
        ApprovalPolicy approvalPolicy,
        SandboxMode sandboxMode,
        Duration requestTimeout,
        int maxTurns,
        int maxOutputCharacters,
        MultiAgentConfig multiAgent
) {
    private static final long MAX_MULTI_AGENT_WAIT_TIMEOUT_MILLIS = 3_600_000;
    private static final Pattern TOOL_NAMESPACE = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final Set<String> RESERVED_TOOL_NAMESPACES = Set.of(
            "functions",
            "computer",
            "browser"
    );

    public CodexConfig {
        Objects.requireNonNull(model);
        Objects.requireNonNull(baseUrl);
        Objects.requireNonNull(codexHome);
        Objects.requireNonNull(workingDirectory);
        Objects.requireNonNull(approvalPolicy);
        Objects.requireNonNull(sandboxMode);
        Objects.requireNonNull(requestTimeout);
        Objects.requireNonNull(multiAgent);
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be positive");
        }
        if (maxOutputCharacters < 1) {
            throw new IllegalArgumentException("maxOutputCharacters must be positive");
        }
    }

    public CodexConfig(
            String model,
            URI baseUrl,
            String apiKey,
            Path codexHome,
            Path workingDirectory,
            ApprovalPolicy approvalPolicy,
            SandboxMode sandboxMode,
            Duration requestTimeout,
            int maxTurns,
            int maxOutputCharacters
    ) {
        this(
                model,
                baseUrl,
                apiKey,
                codexHome,
                workingDirectory,
                approvalPolicy,
                sandboxMode,
                requestTimeout,
                maxTurns,
                maxOutputCharacters,
                MultiAgentConfig.defaults()
        );
    }

    public record MultiAgentConfig(
            boolean enabled,
            int maxConcurrentThreads,
            long minWaitTimeoutMillis,
            long maxWaitTimeoutMillis,
            long defaultWaitTimeoutMillis,
            String toolNamespace
    ) {
        public MultiAgentConfig {
            if (maxConcurrentThreads < 1) {
                throw new IllegalArgumentException("maxConcurrentThreads must be at least 1");
            }
            if (minWaitTimeoutMillis < 0
                    || maxWaitTimeoutMillis < 0
                    || defaultWaitTimeoutMillis < 0
                    || minWaitTimeoutMillis > MAX_MULTI_AGENT_WAIT_TIMEOUT_MILLIS
                    || maxWaitTimeoutMillis > MAX_MULTI_AGENT_WAIT_TIMEOUT_MILLIS
                    || defaultWaitTimeoutMillis > MAX_MULTI_AGENT_WAIT_TIMEOUT_MILLIS
                    || maxWaitTimeoutMillis < minWaitTimeoutMillis) {
                throw new IllegalArgumentException("invalid multi-agent wait timeout range");
            }
            if (defaultWaitTimeoutMillis < minWaitTimeoutMillis
                    || defaultWaitTimeoutMillis > maxWaitTimeoutMillis) {
                throw new IllegalArgumentException("default wait timeout must be within the configured range");
            }
            if (toolNamespace == null || !TOOL_NAMESPACE.matcher(toolNamespace).matches()) {
                throw new IllegalArgumentException(
                        "multi-agent tool namespace must match ^[a-zA-Z0-9_-]+$"
                );
            }
            if (RESERVED_TOOL_NAMESPACES.contains(toolNamespace)) {
                throw new IllegalArgumentException(
                        "multi-agent tool namespace is reserved: " + toolNamespace
                );
            }
        }

        public static MultiAgentConfig defaults() {
            return new MultiAgentConfig(false, 4, 10_000, 3_600_000, 30_000, "collaboration");
        }
    }

    public enum ApprovalPolicy {
        ON_REQUEST,
        NEVER;

        public static ApprovalPolicy parse(String value) {
            return switch (value) {
                case "on-request", "on-failure" -> ON_REQUEST;
                case "never" -> NEVER;
                default -> throw new IllegalArgumentException("Unsupported approval policy: " + value);
            };
        }
    }

    public enum SandboxMode {
        READ_ONLY,
        WORKSPACE_WRITE,
        DANGER_FULL_ACCESS;

        public static SandboxMode parse(String value) {
            return switch (value) {
                case "read-only" -> READ_ONLY;
                case "workspace-write" -> WORKSPACE_WRITE;
                case "danger-full-access" -> DANGER_FULL_ACCESS;
                default -> throw new IllegalArgumentException("Unsupported sandbox mode: " + value);
            };
        }
    }
}
