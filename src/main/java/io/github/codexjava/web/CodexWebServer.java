package io.github.codexjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.codexjava.agent.AgentExecutionContext;
import io.github.codexjava.agent.CodexAgent;
import io.github.codexjava.agent.SessionStore;
import io.github.codexjava.cli.CodexRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class CodexWebServer implements AutoCloseable {
    private final CodexRuntime runtime;
    private final ObjectMapper mapper;
    private final HttpServer server;
    private final Map<String, AgentExecutionContext> threads = new ConcurrentHashMap<>();

    public CodexWebServer(CodexRuntime runtime, String host, int port) throws IOException {
        this.runtime = runtime;
        this.mapper = new ObjectMapper();
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "codex-java-web");
            thread.setDaemon(true);
            return thread;
        }));
        this.server.createContext("/", this::handleStatic);
        this.server.createContext("/api/config", this::handleConfig);
        this.server.createContext("/api/sessions", this::handleSessions);
        this.server.createContext("/api/threads", this::handleThreads);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendStatus(exchange, 405);
            return;
        }
        String resource = switch (path) {
            case "/", "/index.html" -> "/web/index.html";
            case "/app.css" -> "/web/app.css";
            case "/app.js" -> "/web/app.js";
            default -> null;
        };
        if (resource == null) {
            sendStatus(exchange, 404);
            return;
        }
        try (InputStream input = CodexWebServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                sendStatus(exchange, 404);
                return;
            }
            byte[] body = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(resource));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendStatus(exchange, 405);
            return;
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("model", runtime.config().model());
        response.put("cwd", runtime.config().workingDirectory().toString());
        response.put("sandbox", runtime.config().sandboxMode().name().toLowerCase().replace('_', '-'));
        response.put("approval", runtime.config().approvalPolicy().name().toLowerCase().replace('_', '-'));
        response.put("multi_agent", runtime.config().multiAgent().enabled());
        sendJson(exchange, 200, response);
    }

    private void handleSessions(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendStatus(exchange, 405);
            return;
        }
        var sessions = mapper.createArrayNode();
        for (SessionStore.SessionSummary summary : runtime.sessionStore().list(30)) {
            ObjectNode item = sessions.addObject();
            item.put("id", summary.id());
            item.put("timestamp", summary.timestamp());
            item.put("cwd", summary.cwd());
            item.put("model", summary.model());
        }
        ObjectNode response = mapper.createObjectNode();
        response.set("sessions", sessions);
        sendJson(exchange, 200, response);
    }

    private void handleThreads(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(exchange.getRequestMethod()) && "/api/threads".equals(path)) {
            createThread(exchange);
            return;
        }
        String prefix = "/api/threads/";
        if ("POST".equals(exchange.getRequestMethod())
                && path.startsWith(prefix)
                && path.endsWith("/turn")) {
            String threadId = path.substring(prefix.length(), path.length() - "/turn".length());
            runTurn(exchange, threadId);
            return;
        }
        sendStatus(exchange, 404);
    }

    private void createThread(HttpExchange exchange) throws IOException {
        SessionStore.Session session = runtime.agent().newSession();
        AgentExecutionContext context = runtime.registerRoot(session);
        threads.put(session.id(), context);
        ObjectNode response = mapper.createObjectNode();
        response.put("thread_id", session.id());
        response.put("model", runtime.config().model());
        response.put("cwd", runtime.config().workingDirectory().toString());
        sendJson(exchange, 201, response);
    }

    private void runTurn(HttpExchange exchange, String threadId) throws IOException {
        AgentExecutionContext context = threads.get(threadId);
        if (context == null) {
            sendError(exchange, 404, "Unknown or expired thread: " + threadId);
            return;
        }
        JsonNode request;
        try {
            request = mapper.readTree(exchange.getRequestBody());
        } catch (Exception error) {
            sendError(exchange, 400, "Invalid JSON request");
            return;
        }
        String prompt = request.path("prompt").asText();
        if (prompt.isBlank()) {
            sendError(exchange, 400, "prompt is required");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream output = exchange.getResponseBody()) {
            Object lock = new Object();
            CodexAgent.TurnResult result;
            synchronized (context) {
                result = runtime.agent().runTurn(
                        context,
                        userMessage(prompt),
                        ignored -> {
                        },
                        event -> writeEvent(output, lock, event)
                );
            }
            ObjectNode done = mapper.createObjectNode();
            done.put("type", "stream.done");
            done.put("thread_id", result.sessionId());
            writeEvent(output, lock, done);
        } catch (Exception error) {
            System.err.println("Web turn failed: "
                    + Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
        } finally {
            exchange.close();
        }
    }

    private ObjectNode userMessage(String prompt) {
        ObjectNode message = mapper.createObjectNode();
        message.put("type", "message");
        message.put("role", "user");
        message.putArray("content")
                .addObject()
                .put("type", "input_text")
                .put("text", prompt);
        return message;
    }

    private void writeEvent(OutputStream output, Object lock, ObjectNode event) {
        synchronized (lock) {
            try {
                output.write(mapper.writeValueAsBytes(event));
                output.write('\n');
                output.flush();
            } catch (IOException error) {
                throw new StreamWriteException(error);
            }
        }
    }

    private void sendJson(HttpExchange exchange, int status, JsonNode node) throws IOException {
        byte[] body = mapper.writeValueAsBytes(node);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode error = mapper.createObjectNode();
        error.put("error", message);
        sendJson(exchange, status, error);
    }

    private static void sendStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    private static final class StreamWriteException extends RuntimeException {
        private StreamWriteException(IOException cause) {
            super(cause);
        }
    }
}
