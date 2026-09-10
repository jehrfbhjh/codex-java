package io.github.codexjava.tools;

import io.github.codexjava.config.CodexConfig;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspacePolicy {
    private final Path workspace;
    private final CodexConfig.SandboxMode mode;

    public WorkspacePolicy(Path workspace, CodexConfig.SandboxMode mode) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.mode = mode;
    }

    public Path resolveWorkingDirectory(String value) {
        Path candidate = value == null || value.isBlank()
                ? workspace
                : Path.of(value).toAbsolutePath().normalize();
        if (mode != CodexConfig.SandboxMode.DANGER_FULL_ACCESS && !candidate.startsWith(workspace)) {
            throw new SecurityException("Working directory is outside the workspace: " + candidate);
        }
        if (!Files.exists(candidate)) {
            throw new IllegalArgumentException("Working directory does not exist: " + candidate);
        }
        if (!Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("Working directory is not a directory: " + candidate);
        }
        return candidate;
    }

    public Path resolveWritablePath(String value) {
        if (mode == CodexConfig.SandboxMode.READ_ONLY) {
            throw new SecurityException("Writes are disabled by read-only sandbox mode");
        }
        Path candidate = Path.of(value);
        if (!candidate.isAbsolute()) {
            candidate = workspace.resolve(candidate);
        }
        candidate = candidate.toAbsolutePath().normalize();
        if (mode != CodexConfig.SandboxMode.DANGER_FULL_ACCESS && !candidate.startsWith(workspace)) {
            throw new SecurityException("Write path is outside the workspace: " + candidate);
        }
        return candidate;
    }

    public boolean commandNeedsApproval(String command) {
        if (mode == CodexConfig.SandboxMode.DANGER_FULL_ACCESS) {
            return false;
        }
        String normalized = command.toLowerCase();
        return normalized.contains("sudo ")
                || normalized.contains(" rm ")
                || normalized.startsWith("rm ")
                || normalized.contains("git reset")
                || normalized.contains("git clean")
                || normalized.contains("git push")
                || normalized.contains("curl ")
                || normalized.contains("wget ")
                || normalized.contains("npm publish")
                || normalized.contains("mvn deploy");
    }

    public Path workspace() {
        return workspace;
    }
}
