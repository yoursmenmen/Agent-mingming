package com.mingming.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class McpStdioClient {

    private final ObjectMapper objectMapper;

    public Map<String, Object> postJson(McpServerConfig server, Map<String, Object> payload) {
        if (server == null) {
            throw new IllegalArgumentException("server config is required");
        }
        String command = safe(server.command()).trim();
        if (command.isBlank()) {
            throw new IllegalArgumentException("stdio server command is required: " + safe(server.name()));
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        if (server.args() != null) {
            cmd.addAll(server.args().stream().filter(arg -> arg != null && !arg.isBlank()).toList());
        }

        ProcessBuilder builder = new ProcessBuilder(cmd);
        if (server.env() != null && !server.env().isEmpty()) {
            builder.environment().putAll(server.env());
        }

        int timeoutMs = server.timeoutMs() > 0 ? server.timeoutMs() : (int) Duration.ofSeconds(10).toMillis();
        Process process;
        try {
            process = builder.start();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to start stdio mcp process: " + ex.getMessage(), ex);
        }

        try {
            String request = objectMapper.writeValueAsString(payload) + "\n";
            process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();

            var executor = Executors.newSingleThreadExecutor();
            try {
                var future = executor.submit(() -> readFirstJsonLine(process));
                Map<String, Object> response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (response == null || response.isEmpty()) {
                    throw new IllegalStateException("empty stdio response");
                }
                return response;
            } finally {
                executor.shutdownNow();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("stdio mcp call failed: " + ex.getMessage(), ex);
        } finally {
            process.destroyForcibly();
        }
    }

    private Map<String, Object> readFirstJsonLine(Process process) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                if (!trimmed.startsWith("{")) {
                    continue;
                }
                return objectMapper.readValue(trimmed, new TypeReference<LinkedHashMap<String, Object>>() {});
            }
        }
        return Map.of();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
