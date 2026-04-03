package com.mingming.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class McpStdioClient {

    private static final Logger log = LoggerFactory.getLogger(McpStdioClient.class);

    private final ObjectMapper objectMapper;
    private final Map<String, StdioSession> sessions = new ConcurrentHashMap<>();

    public Map<String, Object> postJson(McpServerConfig server, Map<String, Object> payload) {
        if (server == null) {
            throw new IllegalArgumentException("server config is required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }

        String command = safe(server.command()).trim();
        if (command.isBlank()) {
            throw new IllegalArgumentException("stdio server command is required: " + safe(server.name()));
        }

        String requestId = safe(payload.get("id")).trim();
        if (requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            payload = new LinkedHashMap<>(payload);
            payload.put("id", requestId);
        }

        int timeoutMs = server.timeoutMs() > 0 ? server.timeoutMs() : (int) Duration.ofSeconds(10).toMillis();
        String sessionKey = resolveSessionKey(server);

        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            StdioSession session = getOrCreateSession(sessionKey, server);
            try {
                return session.request(payload, requestId, timeoutMs);
            } catch (RuntimeException ex) {
                lastError = ex;
                if (attempt == 0 && !session.isUsable()) {
                    invalidateSession(sessionKey, session);
                    continue;
                }
                throw ex;
            }
        }
        throw lastError == null ? new IllegalStateException("stdio mcp call failed") : lastError;
    }

    private StdioSession getOrCreateSession(String sessionKey, McpServerConfig server) {
        SessionSpec spec = SessionSpec.from(server);
        StdioSession existing = sessions.get(sessionKey);
        if (existing != null && existing.matches(spec) && existing.isUsable()) {
            return existing;
        }

        synchronized (this) {
            StdioSession current = sessions.get(sessionKey);
            if (current != null && current.matches(spec) && current.isUsable()) {
                return current;
            }
            if (current != null) {
                invalidateSession(sessionKey, current);
            }

            StdioSession created = new StdioSession(sessionKey, spec);
            sessions.put(sessionKey, created);
            return created;
        }
    }

    private void invalidateSession(String sessionKey, StdioSession session) {
        if (session == null) {
            return;
        }
        sessions.remove(sessionKey, session);
        session.close("session invalidated");
    }

    private String resolveSessionKey(McpServerConfig server) {
        String byName = safe(server.name()).trim();
        if (!byName.isBlank()) {
            return byName;
        }
        String command = safe(server.command()).trim();
        return command.isBlank() ? "stdio-default" : command;
    }

    @PreDestroy
    void shutdown() {
        List<StdioSession> snapshot = new ArrayList<>(sessions.values());
        sessions.clear();
        snapshot.forEach(session -> session.close("client shutdown"));
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private final class StdioSession {

        private final String sessionKey;
        private final SessionSpec spec;
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader stdout;
        private final BufferedReader stderr;
        private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Object writeLock = new Object();

        private StdioSession(String sessionKey, SessionSpec spec) {
            this.sessionKey = sessionKey;
            this.spec = spec;
            try {
                ProcessBuilder builder = new ProcessBuilder(buildCommand(spec));
                if (spec.workingDir() != null && !spec.workingDir().isBlank()) {
                    builder.directory(new java.io.File(spec.workingDir()));
                }
                if (!spec.env().isEmpty()) {
                    builder.environment().putAll(spec.env());
                }
                this.process = builder.start();
                this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                this.stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
                startReaders();
                log.info("MCP stdio session started: server={}, command={}", sessionKey, spec.command());
            } catch (Exception ex) {
                throw new IllegalStateException("failed to start stdio mcp process: " + ex.getMessage(), ex);
            }
        }

        private boolean matches(SessionSpec other) {
            return Objects.equals(spec, other);
        }

        private boolean isUsable() {
            return !closed.get() && process.isAlive();
        }

        private Map<String, Object> request(Map<String, Object> payload, String requestId, int timeoutMs) {
            if (!isUsable()) {
                throw new IllegalStateException("stdio session unavailable: " + sessionKey);
            }

            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            if (pending.putIfAbsent(requestId, future) != null) {
                throw new IllegalStateException("duplicate stdio request id: " + requestId);
            }

            try {
                String request = objectMapper.writeValueAsString(payload);
                synchronized (writeLock) {
                    writer.write(request);
                    writer.newLine();
                    writer.flush();
                }

                Map<String, Object> response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (response == null || response.isEmpty()) {
                    throw new IllegalStateException("empty stdio response");
                }
                return response;
            } catch (TimeoutException ex) {
                pending.remove(requestId);
                throw new IllegalStateException("stdio mcp call timeout: " + timeoutMs + "ms", ex);
            } catch (Exception ex) {
                pending.remove(requestId);
                throw new IllegalStateException("stdio mcp call failed: " + ex.getMessage(), ex);
            }
        }

        private void startReaders() {
            Thread outThread = new Thread(this::stdoutLoop, "mcp-stdio-out-" + sanitizeThreadName(sessionKey));
            outThread.setDaemon(true);
            outThread.start();

            Thread errThread = new Thread(this::stderrLoop, "mcp-stdio-err-" + sanitizeThreadName(sessionKey));
            errThread.setDaemon(true);
            errThread.start();
        }

        private void stdoutLoop() {
            try {
                String line;
                while (!closed.get() && (line = stdout.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isBlank() || !trimmed.startsWith("{")) {
                        continue;
                    }
                    Map<String, Object> response = objectMapper.readValue(
                            trimmed,
                            new TypeReference<LinkedHashMap<String, Object>>() {});
                    String id = safe(response.get("id")).trim();
                    if (id.isBlank()) {
                        continue;
                    }
                    CompletableFuture<Map<String, Object>> future = pending.remove(id);
                    if (future != null) {
                        future.complete(response);
                    }
                }
            } catch (Exception ex) {
                if (!closed.get()) {
                    failPending("stdio stdout loop failed: " + ex.getMessage());
                }
            } finally {
                close("stdout closed");
            }
        }

        private void stderrLoop() {
            try {
                String line;
                while (!closed.get() && (line = stderr.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isBlank()) {
                        log.debug("MCP stdio stderr: server={}, message={}", sessionKey, trimmed);
                    }
                }
            } catch (Exception ignored) {
                // no-op
            }
        }

        private void failPending(String reason) {
            List<CompletableFuture<Map<String, Object>>> snapshot = new ArrayList<>(pending.values());
            pending.clear();
            for (CompletableFuture<Map<String, Object>> future : snapshot) {
                future.completeExceptionally(new IllegalStateException(reason));
            }
        }

        private void close(String reason) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            failPending("stdio session closed: " + reason);
            try {
                writer.close();
            } catch (Exception ignored) {
                // no-op
            }
            try {
                stdout.close();
            } catch (Exception ignored) {
                // no-op
            }
            try {
                stderr.close();
            } catch (Exception ignored) {
                // no-op
            }
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            sessions.remove(sessionKey, this);
            log.info("MCP stdio session closed: server={}, reason={}", sessionKey, safe(reason));
        }

        private String sanitizeThreadName(String value) {
            String normalized = safe(value).replaceAll("[^A-Za-z0-9_\\-]", "_");
            return normalized.isBlank() ? "default" : normalized;
        }
    }

    private List<String> buildCommand(SessionSpec spec) {
        List<String> command = new ArrayList<>();
        command.add(spec.command());
        command.addAll(spec.args());
        return command;
    }

    private record SessionSpec(String command, String workingDir, List<String> args, Map<String, String> env) {

        private static SessionSpec from(McpServerConfig server) {
            String command = server.command() == null ? "" : server.command().trim();
            String workingDir = server.workingDir() == null ? "" : server.workingDir().trim();
            List<String> args = server.args() == null
                    ? List.of()
                    : Collections.unmodifiableList(server.args().stream()
                            .filter(arg -> arg != null && !arg.isBlank())
                            .toList());
            Map<String, String> env = server.env() == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(server.env()));
            return new SessionSpec(command, workingDir, args, env);
        }
    }
}
