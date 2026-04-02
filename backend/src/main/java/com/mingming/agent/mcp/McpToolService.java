package com.mingming.agent.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class McpToolService {

    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);

    private final McpServerRegistry registry;
    private final McpHttpClient mcpHttpClient;
    private final Map<String, Boolean> enabledOverrides = new ConcurrentHashMap<>();
    private final Map<String, PendingAction> pendingActions = new ConcurrentHashMap<>();

    @Value("${agent.mcp.confirmation.enabled:true}")
    private boolean confirmationEnabled;

    @Value("${agent.mcp.confirmation.pending-ttl-seconds:300}")
    private int pendingTtlSeconds;

    private static final Pattern HARD_BLOCK_RM_WILDCARD = Pattern.compile("(?i)\\brm\\b.*\\*");
    private static final Pattern HARD_BLOCK_RM_ROOT = Pattern.compile("(?i)\\brm\\b.*(\\s/|\\s~|\\s\\.\\./|\\s/\\*)");
    private static final Pattern CONFIRM_INSTALL = Pattern.compile("(?i)\\b(apt|apt-get|yum|dnf|pip|pip3|npm|pnpm|yarn|brew|choco|winget)\\b.*\\b(install|update|upgrade|remove|uninstall)\\b");
    private static final Pattern CONFIRM_MUTATION = Pattern.compile("(?i)\\b(kubectl\\s+(apply|delete|patch|scale|edit)|docker\\s+(run|exec|rm|rmi|compose\\s+up)|git\\s+(push|reset|clean)|chmod|chown|mv|cp|mkdir|touch)\\b");

    public McpToolService(McpServerRegistry registry, McpHttpClient mcpHttpClient) {
        this.registry = registry;
        this.mcpHttpClient = mcpHttpClient;
    }

    private record PendingAction(
            String actionId,
            String server,
            String tool,
            Map<String, Object> arguments,
            String source,
            String reason,
            long createdAtEpochMs,
            long expiresAtEpochMs) {}

    public Map<String, Object> listTools() {
        List<Map<String, Object>> discoveredTools = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<McpServerConfig> servers = enabledHttpServers();
        log.debug("MCP listTools start: enabledHttpServers={}", servers.size());

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

        log.debug("MCP listTools completed: discoveredTools={}, errors={}", discoveredTools.size(), errors.size());

        return Map.of("tools", discoveredTools, "errors", errors);
    }

    public Map<String, Object> listServersWithTools() {
        List<Map<String, Object>> servers = new ArrayList<>();
        List<McpServerConfig> configured = configuredServers();
        log.debug("MCP listServers start: configuredServers={}", configured.size());
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
        log.debug("MCP listServers completed: returnedServers={}", servers.size());
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
        return callTool(serverName, toolName, arguments, "unknown");
    }

    public Map<String, Object> callTool(String serverName, String toolName, Map<String, Object> arguments, String source) {
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

        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        CallGateDecision gateDecision = evaluateCallGate(toolName, safeArguments);
        if (gateDecision.hardBlocked()) {
            log.warn(
                    "MCP callTool blocked: source={}, server={}, tool={}, args={}, reason={}",
                    safe(source),
                    safe(server.name()),
                    safe(toolName),
                    buildArgumentsSummary(toolName, safeArguments),
                    gateDecision.reason());
            return wrapResult(server.name(), toolName, Map.of(
                    "ok", false,
                    "status", "BLOCKED_POLICY",
                    "executed", false,
                    "error", gateDecision.reason(),
                    "server", server.name(),
                    "tool", toolName));
        }

        if (gateDecision.requiresConfirmation()) {
            PendingAction pending = createPendingAction(server.name(), toolName, safeArguments, source, gateDecision.reason());
            log.info(
                    "MCP callTool pending confirmation: actionId={}, source={}, server={}, tool={}, args={}, reason={}, expiresAt={}",
                    pending.actionId(),
                    safe(source),
                    pending.server(),
                    pending.tool(),
                    buildArgumentsSummary(toolName, safeArguments),
                    pending.reason(),
                    pending.expiresAtEpochMs());
            return wrapResult(server.name(), toolName, Map.of(
                    "ok", true,
                    "status", "PENDING_CONFIRMATION",
                    "deferred", true,
                    "executed", false,
                    "actionId", pending.actionId(),
                    "reason", pending.reason(),
                    "expiresAt", pending.expiresAtEpochMs(),
                    "server", pending.server(),
                    "tool", pending.tool(),
                    "arguments", pending.arguments()));
        }

        Map<String, Object> result = executeToolCall(server, toolName, safeArguments, source);
        return wrapResult(server.name(), toolName, result);
    }

    public Map<String, Object> listPendingActions() {
        pruneExpiredPendingActions();
        List<Map<String, Object>> actions = pendingActions.values().stream()
                .sorted(Comparator.comparing(PendingAction::createdAtEpochMs).reversed())
                .map(action -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("actionId", action.actionId());
                    payload.put("server", action.server());
                    payload.put("tool", action.tool());
                    payload.put("source", action.source());
                    payload.put("reason", action.reason());
                    payload.put("arguments", action.arguments());
                    payload.put("createdAt", action.createdAtEpochMs());
                    payload.put("expiresAt", action.expiresAtEpochMs());
                    return payload;
                })
                .toList();
        return Map.of("actions", actions);
    }

    public Map<String, Object> confirmPendingAction(String actionId) {
        pruneExpiredPendingActions();
        PendingAction action = pendingActions.remove(actionId);
        if (action == null) {
            throw new IllegalArgumentException("pending action not found: " + safe(actionId));
        }

        McpServerConfig server = enabledHttpServers().stream()
                .filter(cfg -> Objects.equals(cfg.name(), action.server()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("enabled HTTP MCP server not found: " + action.server()));

        Map<String, Object> result;
        String status = "CONFIRMED_EXECUTED";
        try {
            result = executeToolCall(server, action.tool(), action.arguments(), "api:mcp-confirm");
        } catch (RuntimeException ex) {
            status = "CONFIRM_EXECUTION_FAILED";
            result = Map.of(
                    "ok", false,
                    "executed", false,
                    "status", status,
                    "error", safe(ex.getMessage()),
                    "server", action.server(),
                    "tool", action.tool());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionId", action.actionId());
        payload.put("status", status);
        payload.put("server", action.server());
        payload.put("tool", action.tool());
        payload.put("result", result);
        return payload;
    }

    public Map<String, Object> rejectPendingAction(String actionId) {
        pruneExpiredPendingActions();
        PendingAction action = pendingActions.remove(actionId);
        if (action == null) {
            throw new IllegalArgumentException("pending action not found: " + safe(actionId));
        }
        return Map.of(
                "actionId", action.actionId(),
                "status", "REJECTED",
                "server", action.server(),
                "tool", action.tool(),
                "reason", action.reason());
    }

    private Map<String, Object> executeToolCall(
            McpServerConfig server,
            String toolName,
            Map<String, Object> safeArguments,
            String source) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", safeArguments);

        long start = System.currentTimeMillis();
        Map<String, Object> response;
        Map<String, Object> result;
        try {
            response = callRpc(server, "tools/call", params);
            result = extractResult(response);
        } catch (RuntimeException ex) {
            long elapsedMs = Math.max(1L, System.currentTimeMillis() - start);
            log.warn(
                    "MCP callTool failed: source={}, server={}, tool={}, args={}, elapsedMs={}, message={}",
                    safe(source),
                    safe(server.name()),
                    safe(toolName),
                    buildArgumentsSummary(toolName, safeArguments),
                    elapsedMs,
                    safe(ex.getMessage()));
            throw ex;
        }

        long elapsedMs = Math.max(1L, System.currentTimeMillis() - start);
        log.info(
                "MCP callTool success: source={}, server={}, tool={}, args={}, elapsedMs={}",
                safe(source),
                safe(server.name()),
                safe(toolName),
                buildArgumentsSummary(toolName, safeArguments),
                elapsedMs);

        Map<String, Object> normalized = new LinkedHashMap<>(result);
        normalized.putIfAbsent("executed", true);
        return normalized;
    }

    private Map<String, Object> wrapResult(String server, String toolName, Map<String, Object> result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("server", server);
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

    private record CallGateDecision(boolean hardBlocked, boolean requiresConfirmation, String reason) {}

    private CallGateDecision evaluateCallGate(String toolName, Map<String, Object> arguments) {
        if (!confirmationEnabled) {
            return new CallGateDecision(false, false, "");
        }

        if (!"run_local_command".equals(toolName)) {
            return new CallGateDecision(false, false, "");
        }

        String commandLine = normalizeCommandLine(arguments);
        if (commandLine.isBlank()) {
            return new CallGateDecision(false, true, "run_local_command requires explicit confirmation");
        }
        if (isHardBlockedCommand(commandLine)) {
            return new CallGateDecision(true, false, "hard-blocked destructive command pattern");
        }
        if (isConfirmableMutation(commandLine)) {
            return new CallGateDecision(false, true, "confirm required for mutating command");
        }
        return new CallGateDecision(false, false, "");
    }

    private String normalizeCommandLine(Map<String, Object> arguments) {
        String command = safe(arguments.get("command")).trim();
        Object argsValue = arguments.get("args");
        List<String> args = new ArrayList<>();
        if (argsValue instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    args.add(String.valueOf(item));
                }
            }
        }
        String joinedArgs = String.join(" ", args).trim();
        String line = (command + " " + joinedArgs).trim();
        return line.replaceAll("\\s+", " ");
    }

    private boolean isHardBlockedCommand(String commandLine) {
        String lowered = commandLine.toLowerCase();
        if (lowered.contains("rm -rf /") || lowered.contains("rm -rf /*") || lowered.contains("del /f /s /q c:\\")) {
            return true;
        }
        return HARD_BLOCK_RM_WILDCARD.matcher(commandLine).find() || HARD_BLOCK_RM_ROOT.matcher(commandLine).find();
    }

    private boolean isConfirmableMutation(String commandLine) {
        return CONFIRM_INSTALL.matcher(commandLine).find() || CONFIRM_MUTATION.matcher(commandLine).find();
    }

    private PendingAction createPendingAction(
            String server,
            String tool,
            Map<String, Object> arguments,
            String source,
            String reason) {
        pruneExpiredPendingActions();
        String actionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long ttlMs = Math.max(30L, pendingTtlSeconds) * 1000L;
        PendingAction action = new PendingAction(
                actionId,
                server,
                tool,
                new LinkedHashMap<>(arguments),
                safe(source),
                safe(reason),
                now,
                now + ttlMs);
        pendingActions.put(actionId, action);
        return action;
    }

    private void pruneExpiredPendingActions() {
        long now = System.currentTimeMillis();
        Set<String> expiredIds = new HashSet<>();
        for (Map.Entry<String, PendingAction> entry : pendingActions.entrySet()) {
            if (entry.getValue().expiresAtEpochMs() <= now) {
                expiredIds.add(entry.getKey());
            }
        }
        for (String id : expiredIds) {
            pendingActions.remove(id);
        }
    }

    private String buildArgumentsSummary(String toolName, Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }

        String loweredToolName = safe(toolName).toLowerCase();
        if (arguments.containsKey("url")) {
            String url = truncate(safe(arguments.get("url")), 180);
            return "{url=" + url + ", keys=" + keySummary(arguments) + "}";
        }
        if (loweredToolName.contains("command") || arguments.containsKey("command")) {
            String command = truncate(safe(arguments.get("command")), 120);
            Object args = arguments.get("args");
            int argCount = args instanceof List<?> list ? list.size() : 0;
            return "{command=" + command + ", argCount=" + argCount + ", keys=" + keySummary(arguments) + "}";
        }
        return "{keys=" + keySummary(arguments) + "}";
    }

    private String keySummary(Map<String, Object> arguments) {
        return arguments.keySet().stream().sorted().toList().toString();
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
}
