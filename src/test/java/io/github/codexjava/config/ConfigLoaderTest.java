package io.github.codexjava.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsMissingWorkingDirectoryBeforeRuntimeStarts() {
        Path missing = temporaryDirectory.resolve("missing-project");
        ConfigLoader.Overrides overrides = new ConfigLoader.Overrides(
                "test-model",
                "https://example.com/v1",
                "test-key",
                temporaryDirectory.resolve(".codex"),
                missing,
                "never",
                "workspace-write",
                4
        );

        IOException error = assertThrows(IOException.class, () -> ConfigLoader.load(overrides));

        assertTrue(error.getMessage().contains("Working directory does not exist"));
        assertTrue(error.getMessage().contains(missing.toString()));
        assertTrue(error.getMessage().contains("/path/to/repo"));
    }

    @Test
    void acceptsExistingWorkingDirectory() throws Exception {
        ConfigLoader.Overrides overrides = new ConfigLoader.Overrides(
                "test-model",
                "https://example.com/v1",
                "test-key",
                temporaryDirectory.resolve(".codex"),
                temporaryDirectory,
                "never",
                "workspace-write",
                4
        );

        CodexConfig config = ConfigLoader.load(overrides);

        assertEquals(temporaryDirectory.toAbsolutePath().normalize(), config.workingDirectory());
    }
}
