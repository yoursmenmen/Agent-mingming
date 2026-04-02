package com.mingming.agent.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class McpToolService {

    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);

    private final McpServerRegistry registry;
    private final McpHttpClient mcpHttpClient;
    private final Map<String, Boolean> enabledOverrides = new ConcurrentHashMap<>();

    public McpToolService(McpServerRegistry registry, McpHttpClient mcpHttpClient) {
        this.registry = registry;
        this.mcpHttpClient = mcpHttpClient;
    }

    public Map<String, Object> listTools() {
        List<Map<String, Object>> discoveredTools = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<McpServerConfig> servers = enabledHttpServers();
        log.info("MCP listTools start: enabledHttpServers={}", servers.size());

        for (McpServerConfig server : servers) {
            try {
                Map<String, Object> response = callRpc(server, "tools/list", Map.of());
                Map<String, Object> result = extractResult(response);
                List<Map<String, Object>> tools = toToolList(result.get("tools"));
                for (Map<String, Object> tool : tools) {
                    Map<String, Object> enriched = new LinkedHashMap<>(tool);
                    enriched.put("server", server.name());
                    discoveredTools.add(enriched);
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "MCP tools discovery failed: server={}, transport={}, url={}, message={}",
                        server.name(),
                        server.transport(),
                        server.url(),
                        ex.getMessage());
                errors.add(Map.of(
                        "server", safe(server.name()),
                        "url", safe(server.url()),
                        "message", safe(ex.getMessage())));
            }
        }

        log.info("MCP listTools completed: discoveredTools={}, errors={}", discoveredTools.size(), errors.size());

        return Map.of("tools", discoveredTools, "errors", errors);
    }

    public Map<String, Object> listServersWithTools() {
        List<Map<String, Object>> servers = new ArrayList<>();
        List<McpServerConfig> configured = configuredServers();
        log.info("MCP listServers start: configuredServers={}", configured.size());
        for (McpServerConfig server : configured) {
            Map<String, Object> serverPayload = new LinkedHashMap<>();
            serverPayload.put("name", safe(server.name()));
            serverPayload.put("transport", safe(server.transport()));
            serverPayload.put("url", safe(server.url()));
            serverPayload.put("streaming", safe(server.streaming()));
            serverPayload.put("timeoutMs", server.timeoutMs());
            serverPayload.put("configuredEnabled", server.enabled());
            serverPayload.put("effectiveEnabled", isEnabled(server));

            if (!"http".equalsIgnoreCase(safe(server.transport()))) {
                serverPayload.put("tools", List.of());
                serverPayload.put("lastStatus", "UNSUPPORTED_TRANSPORT");
                servers.add(serverPayload);
                continue;
            }

            if (!isEnabled(server)) {
                serverPayload.put("tools", List.of());
                serverPayload.put("lastStatus", "DISABLED");
                servers.add(serverPayload);
                continue;
            }

            try {
                Map<String, Object> response = callRpc(server, "tools/list", Map.of());
                Map<String, Object> result = extractResult(response);
                List<Map<String, Object>> tools = toToolList(result.get("tools"));
                serverPayload.put("tools", tools);
                serverPayload.put("lastStatus", "SUCCESS");
            } catch (RuntimeException ex) {
                serverPayload.put("tools", List.of());
                serverPayload.put("lastStatus", "FAILED");
                serverPayload.put("lastError", safe(ex.getMessage()));
                log.warn(
                        "MCP server tools/list failed: server={}, transport={}, url={}, message={}",
                        server.name(),
                        server.transport(),
                        server.url(),
                        ex.getMessage());
            }
            servers.add(serverPayload);
        }
        log.info("MCP listServers completed: returnedServers={}", servers.size());
        return Map.of("servers", servers);
    }

    public Map<String, Object> setServerEnabled(String serverName, boolean enabled) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("server is required");
        }
        McpServerConfig server = configuredServers().stream()
                .filter(cfg -> Objects.equals(cfg.name(), serverName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverName));
        enabledOverrides.put(serverName, enabled);
        log.info("MCP server enabled override updated: server={}, enabled={}", serverName, enabled);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", safe(server.name()));
        payload.put("configuredEnabled", server.enabled());
        payload.put("effectiveEnabled", isEnabled(server));
        payload.put("transport", safe(server.transport()));
        payload.put("url", safe(server.url()));
        return payload;
    }

    public Map<String, Object> callTool(String serverName, String toolName, Map<String, Object> arguments) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("server is required");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }

        McpServerConfig server = enabledHttpServers().stream()
                .filter(cfg -> Objects.equals(cfg.name(), serverName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("enabled HTTP MCP server not found: " + serverName));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);

        Map<String, Object> response = callRpc(server, "tools/call", params);
        Map<String, Object> result = extractResult(response);
        log.info("MCP callTool completed: server={}, tool={}", server.name(), toolName);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("server", server.name());
        payload.put("tool", toolName);
        payload.put("result", result);
        return payload;
    }

    private List<McpServerConfig> configuredServers() {
        McpServersConfig config = registry.load();
        if (config == null || config.servers() == null) {
            return List.of();
        }
        return config.servers().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(server -> safe(server.name())))
                .toList();
    }

    private List<McpServerConfig> enabledHttpServers() {
        return configuredServers().stream()
                .filter(this::isEnabled)
                .filter(server -> "http".equalsIgnoreCase(safe(server.transport())))
                .filter(server -> server.url() != null && !server.url().isBlank())
                .toList();
    }

    private boolean isEnabled(McpServerConfig server) {
        Boolean override = enabledOverrides.get(server.name());
        if (override != null) {
            return override;
        }
        return server.enabled();
    }

    private Map<String, Object> callRpc(McpServerConfig server, String method, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", UUID.randomUUID().toString());
        payload.put("method", method);
        payload.put("params", params == null ? Map.of() : params);

        Map<String, Object> response = mcpHttpClient.postJson(server.url(), server.timeoutMs(), payload);
        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("empty response");
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractResult(Map<String, Object> response) {
        Object errorValue = response.get("error");
        if (errorValue instanceof Map<?, ?> error) {
            Object message = error.get("message");
            throw new IllegalStateException("mcp error: " + safe(message));
        }

        Object result = response.get("result");
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new IllegalStateException("missing result field");
        }
        return (Map<String, Object>) resultMap;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toToolList(Object toolsValue) {
        if (!(toolsValue instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> mapItem) {
                out.add((Map<String, Object>) mapItem);
            }
        }
        return out;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
