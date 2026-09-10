package io.github.codexjava.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codexjava.config.CodexConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyPatchToolTest {
    @TempDir
    Path workspace;

    @Test
    void declaresApplyPatchAsStandardFunctionTool() {
        ApplyPatchTool tool = new ApplyPatchTool(
                new ObjectMapper(),
                new WorkspacePolicy(workspace, CodexConfig.SandboxMode.WORKSPACE_WRITE)
        );

        JsonNode specification = tool.specification();

        assertEquals("function", specification.path("type").asText());
        assertEquals("apply_patch", specification.path("name").asText());
        assertEquals("object", specification.path("parameters").path("type").asText());
        assertEquals(
                "string",
                specification.path("parameters").path("properties").path("patch").path("type").asText()
        );
        assertEquals("patch", specification.path("parameters").path("required").get(0).asText());
        assertFalse(specification.path("parameters").path("additionalProperties").asBoolean());
    }

    @Test
    void addsAndUpdatesFiles() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ApplyPatchTool tool = new ApplyPatchTool(
                mapper,
                new WorkspacePolicy(workspace, CodexConfig.SandboxMode.WORKSPACE_WRITE)
        );

        CodexTool.ToolResult added = tool.execute(mapper.getNodeFactory().textNode("""
                *** Begin Patch
                *** Add File: notes/example.txt
                +alpha
                +beta
                *** End Patch
                """.strip()), null);
        assertTrue(added.success());
        assertEquals("alpha\nbeta\n", Files.readString(workspace.resolve("notes/example.txt")));

        CodexTool.ToolResult updated = tool.execute(mapper.getNodeFactory().textNode("""
                *** Begin Patch
                *** Update File: notes/example.txt
                @@
                 alpha
                -beta
                +gamma
                *** End Patch
                """.strip()), null);
        assertTrue(updated.success());
        assertEquals("alpha\ngamma\n", Files.readString(workspace.resolve("notes/example.txt")));
    }

    @Test
    void rejectsWritesOutsideWorkspace() {
        WorkspacePolicy policy = new WorkspacePolicy(workspace, CodexConfig.SandboxMode.WORKSPACE_WRITE);
        assertThrows(SecurityException.class, () -> policy.resolveWritablePath("../escape.txt"));
    }

    @Test
    void rejectsMissingCommandWorkingDirectoryClearly() {
        WorkspacePolicy policy = new WorkspacePolicy(workspace, CodexConfig.SandboxMode.WORKSPACE_WRITE);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> policy.resolveWorkingDirectory(workspace.resolve("missing").toString())
        );
        assertTrue(error.getMessage().contains("Working directory does not exist"));
    }

    @Test
    void readOnlyModeRejectsPatch() {
        ObjectMapper mapper = new ObjectMapper();
        ApplyPatchTool tool = new ApplyPatchTool(
                mapper,
                new WorkspacePolicy(workspace, CodexConfig.SandboxMode.READ_ONLY)
        );
        CodexTool.ToolResult result = tool.execute(mapper.getNodeFactory().textNode("""
                *** Begin Patch
                *** Add File: blocked.txt
                +blocked
                *** End Patch
                """.strip()), null);
        assertFalse(result.success());
    }
}
