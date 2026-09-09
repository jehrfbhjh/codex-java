package io.github.codexjava.config;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static CodexConfig load(Overrides overrides) throws IOException {
        Path codexHome = firstPath(
                overrides.codexHome(),
                System.getenv("CODEX_HOME"),
                Path.of(System.getProperty("user.home"), ".codex")
        );
        TomlParseResult toml = parseConfig(codexHome.resolve("config.toml"));
        String model = first(overrides.model(), env("OPENAI_MODEL"), tomlString(toml, "model"), "gpt-5.4");
        String baseUrl = first(
                overrides.baseUrl(),
                env("OPENAI_BASE_URL"),
                tomlString(toml, "openai_base_url"),
                "https://api.openai.com/v1"
        );
        String apiKey = first(overrides.apiKey(), env("OPENAI_API_KEY"), null);
        String approval = first(
                overrides.approvalPolicy(),
                tomlString(toml, "approval_policy"),
                "on-request"
        );
        String sandbox = first(
                overrides.sandboxMode(),
                tomlString(toml, "sandbox_mode"),
                "workspace-write"
        );
        boolean multiAgentEnabled = tomlBoolean(toml, "features.multi_agent_v2.enabled", false);
        int maxConcurrentThreads = Math.toIntExact(tomlLong(
                toml,
                "features.multi_agent_v2.max_concurrent_threads_per_session",
                4
        ));
        long minWaitTimeoutMillis = tomlLong(
                toml,
                "features.multi_agent_v2.min_wait_timeout_ms",
                10_000
        );
        long maxWaitTimeoutMillis = tomlLong(
                toml,
                "features.multi_agent_v2.max_wait_timeout_ms",
                3_600_000
        );
        long defaultWaitTimeoutMillis = tomlLong(
                toml,
                "features.multi_agent_v2.default_wait_timeout_ms",
                30_000
        );
        String toolNamespace = first(
                tomlString(toml, "features.multi_agent_v2.tool_namespace"),
                "collaboration"
        );
        Path cwd = overrides.workingDirectory() == null
                ? Path.of(System.getProperty("user.dir"))
                : overrides.workingDirectory();

        return new CodexConfig(
                model,
                normalizeBaseUrl(baseUrl),
                apiKey,
                codexHome.toAbsolutePath().normalize(),
                cwd.toAbsolutePath().normalize(),
                CodexConfig.ApprovalPolicy.parse(approval),
                CodexConfig.SandboxMode.parse(sandbox),
                Duration.ofSeconds(180),
                overrides.maxTurns() == null ? 32 : overrides.maxTurns(),
                40_000,
                new CodexConfig.MultiAgentConfig(
                        multiAgentEnabled,
                        maxConcurrentThreads,
                        minWaitTimeoutMillis,
                        maxWaitTimeoutMillis,
                        defaultWaitTimeoutMillis,
                        toolNamespace
                )
        );
    }

    private static TomlParseResult parseConfig(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            return Toml.parse("");
        }
        TomlParseResult result = Toml.parse(configPath);
        if (result.hasErrors()) {
            throw new IOException("Invalid TOML in " + configPath + ": " + result.errors());
        }
        return result;
    }

    private static URI normalizeBaseUrl(String value) {
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return URI.create(normalized);
    }

    private static String tomlString(TomlParseResult result, String key) {
        return result.getString(key);
    }

    private static boolean tomlBoolean(TomlParseResult result, String key, boolean fallback) {
        Boolean value = result.getBoolean(key);
        return value == null ? fallback : value;
    }

    private static long tomlLong(TomlParseResult result, String key, long fallback) {
        Long value = result.getLong(key);
        return value == null ? fallback : value;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Path firstPath(Path direct, String environment, Path fallback) {
        if (direct != null) {
            return direct;
        }
        if (environment != null && !environment.isBlank()) {
            return Path.of(environment);
        }
        return fallback;
    }

    public record Overrides(
            String model,
            String baseUrl,
            String apiKey,
            Path codexHome,
            Path workingDirectory,
            String approvalPolicy,
            String sandboxMode,
            Integer maxTurns
    ) {
        public static Overrides empty() {
            return new Overrides(null, null, null, null, null, null, null, null);
        }

        public Overrides with(Map<String, String> values) {
            return new Overrides(
                    values.getOrDefault("model", model),
                    values.getOrDefault("base_url", baseUrl),
                    values.getOrDefault("api_key", apiKey),
                    codexHome,
                    workingDirectory,
                    values.getOrDefault("approval_policy", approvalPolicy),
                    values.getOrDefault("sandbox_mode", sandboxMode),
                    maxTurns
            );
        }
    }
}
