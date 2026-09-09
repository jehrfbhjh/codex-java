package io.github.codexjava.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InstructionLoader {
    private static final int MAX_INSTRUCTION_CHARACTERS = 40_000;

    public String load(Path workingDirectory) throws IOException {
        List<Path> hierarchy = new ArrayList<>();
        Path current = workingDirectory.toAbsolutePath().normalize();
        while (current != null) {
            hierarchy.add(current);
            current = current.getParent();
        }
        Collections.reverse(hierarchy);

        StringBuilder instructions = new StringBuilder(baseInstructions(workingDirectory));
        for (Path directory : hierarchy) {
            Path agents = directory.resolve("AGENTS.md");
            if (Files.isRegularFile(agents)) {
                appendBounded(instructions, "\n\n# " + agents + "\n"
                        + Files.readString(agents, StandardCharsets.UTF_8));
            }
        }
        return instructions.toString();
    }

    private static String baseInstructions(Path workingDirectory) {
        return """
                You are Codex Java, a coding agent operating on the user's local workspace.
                Inspect relevant files before editing. Keep changes focused and verify them.
                Use exec_command for shell commands and apply_patch for file changes.
                Never claim a command or test succeeded unless its tool output confirms it.
                The current working directory is %s.
                Current local time is %s.
                """.formatted(workingDirectory.toAbsolutePath().normalize(), ZonedDateTime.now());
    }

    private static void appendBounded(StringBuilder target, String value) {
        int remaining = MAX_INSTRUCTION_CHARACTERS - target.length();
        if (remaining > 0) {
            target.append(value, 0, Math.min(value.length(), remaining));
        }
    }
}
