package io.github.codexjava.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.codexjava.agent.SessionStore;
import io.github.codexjava.config.CodexConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliEventRendererTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void jsonRendererEmitsOneJsonObjectPerLine() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CliEventRenderer renderer = CliEventRenderer.json(
                mapper,
                new PrintStream(buffer, true, StandardCharsets.UTF_8)
        );
        CodexConfig config = new CodexConfig(
                "test-model",
                URI.create("http://127.0.0.1:1/v1"),
                "test-key",
                temporaryDirectory.resolve(".codex"),
                temporaryDirectory,
                CodexConfig.ApprovalPolicy.NEVER,
                CodexConfig.SandboxMode.WORKSPACE_WRITE,
                Duration.ofSeconds(10),
                4,
                10_000
        );
        SessionStore.Session session = new SessionStore(mapper, config.codexHome())
                .create(config.workingDirectory(), config.model());
        renderer.threadStarted(session, config);
        ObjectNode turn = mapper.createObjectNode();
        turn.put("type", "turn.started");
        turn.put("thread_id", session.id());
        renderer.onEvent(turn);

        String[] lines = buffer.toString(StandardCharsets.UTF_8).strip().split("\\R");
        assertEquals(2, lines.length);
        JsonNode threadEvent = mapper.readTree(lines[0]);
        JsonNode turnEvent = mapper.readTree(lines[1]);
        assertEquals("thread.started", threadEvent.path("type").asText());
        assertEquals(session.id(), threadEvent.path("thread_id").asText());
        assertEquals("turn.started", turnEvent.path("type").asText());
    }
}
