package io.github.codexjava.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.codexjava.agent.AgentExecutionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class ApplyPatchTool implements CodexTool {
    private static final String BEGIN = "*** Begin Patch";
    private static final String END = "*** End Patch";
    private final ObjectMapper mapper;
    private final WorkspacePolicy workspacePolicy;

    public ApplyPatchTool(ObjectMapper mapper, WorkspacePolicy workspacePolicy) {
        this.mapper = mapper;
        this.workspacePolicy = workspacePolicy;
    }

    @Override
    public String name() {
        return "apply_patch";
    }

    @Override
    public ObjectNode specification() {
        ObjectNode patch = mapper.createObjectNode();
        patch.put("type", "string");
        patch.put(
                "description",
                "Patch in Codex format, beginning with *** Begin Patch and ending with *** End Patch."
        );

        ObjectNode properties = mapper.createObjectNode();
        properties.set("patch", patch);

        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        parameters.putArray("required").add("patch");
        parameters.put("additionalProperties", false);

        ObjectNode spec = mapper.createObjectNode();
        spec.put("type", "function");
        spec.put("name", name());
        spec.put(
                "description",
                "Edits workspace files using the Codex patch format. Begin with *** Begin Patch and end with *** End Patch."
        );
        spec.put("strict", false);
        spec.set("parameters", parameters);
        return spec;
    }

    @Override
    public ToolResult execute(JsonNode arguments, AgentExecutionContext context) {
        String patch = arguments.isTextual()
                ? arguments.asText()
                : arguments.path("patch").asText(arguments.path("input").asText());
        try {
            List<FileOperation> operations = parse(patch);
            List<String> changed = new ArrayList<>();
            for (FileOperation operation : operations) {
                operation.apply();
                changed.add(operation.label());
            }
            return ToolResult.success("Done!\n" + String.join("\n", changed));
        } catch (Exception error) {
            return ToolResult.failure("apply_patch failed: " + error.getMessage());
        }
    }

    List<FileOperation> parse(String patch) throws IOException {
        List<String> lines = patch.lines().toList();
        if (lines.size() < 3 || !BEGIN.equals(lines.get(0)) || !END.equals(lines.get(lines.size() - 1))) {
            throw new IOException("patch must be wrapped in Begin Patch and End Patch markers");
        }
        List<FileOperation> operations = new ArrayList<>();
        int index = 1;
        while (index < lines.size() - 1) {
            String header = lines.get(index++);
            if (header.startsWith("*** Add File: ")) {
                String path = header.substring("*** Add File: ".length());
                List<String> content = new ArrayList<>();
                while (index < lines.size() - 1 && !lines.get(index).startsWith("*** ")) {
                    String line = lines.get(index++);
                    if (!line.startsWith("+")) {
                        throw new IOException("add-file content must start with +");
                    }
                    content.add(line.substring(1));
                }
                operations.add(new AddFile(workspacePolicy.resolveWritablePath(path), content));
            } else if (header.startsWith("*** Update File: ")) {
                String path = header.substring("*** Update File: ".length());
                List<Hunk> hunks = new ArrayList<>();
                while (index < lines.size() - 1 && !lines.get(index).startsWith("*** ")) {
                    if (!lines.get(index).startsWith("@@")) {
                        throw new IOException("update-file section requires an @@ hunk");
                    }
                    index++;
                    List<String> oldLines = new ArrayList<>();
                    List<String> newLines = new ArrayList<>();
                    while (index < lines.size() - 1
                            && !lines.get(index).startsWith("@@")
                            && !lines.get(index).startsWith("*** ")) {
                        String line = lines.get(index++);
                        if (line.isEmpty()) {
                            throw new IOException("hunk line requires a prefix");
                        }
                        switch (line.charAt(0)) {
                            case ' ' -> {
                                oldLines.add(line.substring(1));
                                newLines.add(line.substring(1));
                            }
                            case '-' -> oldLines.add(line.substring(1));
                            case '+' -> newLines.add(line.substring(1));
                            default -> throw new IOException("invalid hunk prefix: " + line.charAt(0));
                        }
                    }
                    hunks.add(new Hunk(oldLines, newLines));
                }
                operations.add(new UpdateFile(workspacePolicy.resolveWritablePath(path), hunks));
            } else {
                throw new IOException("unsupported patch operation: " + header);
            }
        }
        if (operations.isEmpty()) {
            throw new IOException("patch contains no file operations");
        }
        return operations;
    }

    sealed interface FileOperation permits AddFile, UpdateFile {
        void apply() throws IOException;

        String label();
    }

    record AddFile(Path path, List<String> content) implements FileOperation {
        @Override
        public void apply() throws IOException {
            if (Files.exists(path)) {
                throw new IOException("file already exists: " + path);
            }
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, String.join("\n", content) + "\n", StandardCharsets.UTF_8);
        }

        @Override
        public String label() {
            return "A " + path;
        }
    }

    record UpdateFile(Path path, List<Hunk> hunks) implements FileOperation {
        @Override
        public void apply() throws IOException {
            if (!Files.isRegularFile(path)) {
                throw new IOException("file does not exist: " + path);
            }
            String original = Files.readString(path, StandardCharsets.UTF_8);
            boolean trailingNewline = original.endsWith("\n");
            List<String> current = new ArrayList<>(original.lines().toList());
            for (Hunk hunk : hunks) {
                int offset = find(current, hunk.oldLines());
                if (offset < 0) {
                    throw new IOException("hunk context not found in " + path);
                }
                current.subList(offset, offset + hunk.oldLines().size()).clear();
                current.addAll(offset, hunk.newLines());
            }
            String replacement = String.join("\n", current) + (trailingNewline ? "\n" : "");
            Path temporary = Files.createTempFile(path.getParent(), ".codex-java-", ".patch");
            Files.writeString(temporary, replacement, StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        private static int find(List<String> content, List<String> expected) {
            if (expected.isEmpty()) {
                return content.size();
            }
            for (int index = 0; index <= content.size() - expected.size(); index++) {
                if (content.subList(index, index + expected.size()).equals(expected)) {
                    return index;
                }
            }
            return -1;
        }

        @Override
        public String label() {
            return "M " + path;
        }
    }

    record Hunk(List<String> oldLines, List<String> newLines) {
    }
}
