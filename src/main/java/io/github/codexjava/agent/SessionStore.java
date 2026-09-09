package io.github.codexjava.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class SessionStore {
    private static final DateTimeFormatter DIRECTORY_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private final ObjectMapper mapper;
    private final Path sessionsRoot;

    public SessionStore(ObjectMapper mapper, Path codexHome) {
        this.mapper = mapper;
        this.sessionsRoot = codexHome.resolve("java-sessions");
    }

    public Session create(Path cwd, String model) throws IOException {
        return create(cwd, model, mapper.createArrayNode());
    }

    public Session create(Path cwd, String model, ArrayNode initialInput) throws IOException {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Path directory = sessionsRoot.resolve(DIRECTORY_DATE.format(now));
        Files.createDirectories(directory);
        Path file = directory.resolve("rollout-" + id + ".jsonl");
        Session session = new Session(id, file, initialInput.deepCopy());
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("type", "session_meta");
        metadata.put("id", id);
        metadata.put("timestamp", now.toString());
        metadata.put("cwd", cwd.toString());
        metadata.put("model", model);
        append(file, metadata);
        for (JsonNode item : initialInput) {
            appendItemEvent(file, item);
        }
        return session;
    }

    public Session resumeLast() throws IOException {
        if (!Files.isDirectory(sessionsRoot)) {
            throw new IOException("No saved Java sessions found");
        }
        Path latest;
        try (Stream<Path> paths = Files.walk(sessionsRoot)) {
            latest = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .max(Comparator.comparingLong(this::modifiedTime))
                    .orElseThrow(() -> new IOException("No saved Java sessions found"));
        }
        return load(latest);
    }

    public Session resume(String sessionId) throws IOException {
        try {
            UUID.fromString(sessionId);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid session id", error);
        }
        if (!Files.isDirectory(sessionsRoot)) {
            throw new IOException("No saved Java sessions found");
        }
        String expectedName = "rollout-" + sessionId + ".jsonl";
        Path sessionFile;
        try (Stream<Path> paths = Files.walk(sessionsRoot)) {
            sessionFile = paths.filter(path -> expectedName.equals(path.getFileName().toString()))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Unknown session: " + sessionId));
        }
        return load(sessionFile);
    }

    private Session load(Path file) throws IOException {
        ArrayNode input = mapper.createArrayNode();
        String id = file.getFileName().toString()
                .replace("rollout-", "")
                .replace(".jsonl", "");
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            JsonNode event = mapper.readTree(line);
            if ("response_item".equals(event.path("type").asText()) && event.has("item")) {
                input.add(event.get("item"));
            }
        }
        return new Session(id, file, input);
    }

    public void appendItem(Session session, JsonNode item) throws IOException {
        synchronized (session) {
            session.input().add(item.deepCopy());
            appendItemEvent(session.file(), item);
        }
    }

    public ArrayNode snapshotInput(Session session) {
        synchronized (session) {
            return session.input().deepCopy();
        }
    }

    public List<SessionSummary> list(int limit) throws IOException {
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> paths = Files.walk(sessionsRoot)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparingLong(this::modifiedTime).reversed())
                    .limit(limit)
                    .toList();
        }
        List<SessionSummary> summaries = new ArrayList<>();
        for (Path file : files) {
            JsonNode first = mapper.readTree(Files.readAllLines(file, StandardCharsets.UTF_8).get(0));
            summaries.add(new SessionSummary(
                    first.path("id").asText(),
                    first.path("timestamp").asText(),
                    first.path("cwd").asText(),
                    first.path("model").asText(),
                    file
            ));
        }
        return summaries;
    }

    private void append(Path file, JsonNode event) throws IOException {
        Files.writeString(
                file,
                mapper.writeValueAsString(event) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    private void appendItemEvent(Path file, JsonNode item) throws IOException {
        ObjectNode event = mapper.createObjectNode();
        event.put("type", "response_item");
        event.put("timestamp", Instant.now().toString());
        event.set("item", item);
        append(file, event);
    }

    private long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0;
        }
    }

    public record Session(String id, Path file, ArrayNode input) {
    }

    public record SessionSummary(String id, String timestamp, String cwd, String model, Path file) {
    }
}
