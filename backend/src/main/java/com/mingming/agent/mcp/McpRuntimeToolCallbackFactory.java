package com.mingming.agent.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

@Component
public class McpRuntimeToolCallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(McpRuntimeToolCallbackFactory.class);

    private final McpToolService mcpToolService;

    public McpRuntimeToolCallbackFactory(McpToolService mcpToolService) {
        this.mcpToolService = mcpToolService;
    }

    public List<ToolCallback> createCallbacks() {
        try {
            Map<String, Object> toolDiscovery = mcpToolService.listTools();
            List<Map<String, Object>> discoveredTools = toMapList(toolDiscovery.get("tools"));
            if (discoveredTools.isEmpty()) {
                return List.of();
            }

            List<ToolCallback> callbacks = new ArrayList<>();
            Set<String> usedNames = new LinkedHashSet<>();
            for (Map<String, Object> discoveredTool : discoveredTools) {
                String server = safeString(discoveredTool.get("server"));
                String originalToolName = safeString(discoveredTool.get("name"));
                if (server.isBlank() || originalToolName.isBlank()) {
                    continue;
                }
                String callbackName = uniqueName(buildToolName(server, originalToolName), usedNames);
                String description = buildDescription(server, originalToolName, safeString(discoveredTool.get("description")));
                Function<Map<String, Object>, Map<String, Object>> handler =
                        args -> executeTool(server, originalToolName, args);

                callbacks.add(FunctionToolCallback.builder(callbackName, handler)
                        .description(description)
                        .inputType(Map.class)
                        .build());
            }

            log.info("MCP runtime tool callbacks ready: count={}", callbacks.size());
            return List.copyOf(callbacks);
        } catch (RuntimeException ex) {
            log.warn("MCP runtime tool callbacks unavailable: message={}", ex.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> executeTool(String server, String toolName, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        Map<String, Object> response = mcpToolService.callTool(server, toolName, safeArgs);
        Object result = response.get("result");
        if (result instanceof Map<?, ?> resultMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) resultMap;
            return typed;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("ok", true);
        fallback.put("result", result);
        fallback.put("server", server);
        fallback.put("tool", toolName);
        return fallback;
    }

    private String buildDescription(String server, String originalToolName, String originalDescription) {
        StringBuilder builder = new StringBuilder();
        builder.append("Call MCP tool '")
                .append(originalToolName)
                .append("' on server '")
                .append(server)
                .append("'.");
        if (!originalDescription.isBlank()) {
            builder.append(' ').append(originalDescription);
        }
        return builder.toString();
    }

    private String buildToolName(String server, String originalToolName) {
        return sanitize("mcp_" + server + "_" + originalToolName);
    }

    private String uniqueName(String baseName, Set<String> usedNames) {
        String candidate = baseName;
        int suffix = 2;
        while (!usedNames.add(candidate)) {
            candidate = baseName + "_" + suffix++;
        }
        return candidate;
    }

    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "mcp_tool";
        }
        String normalized = raw.trim().replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        if (normalized.isBlank()) {
            return "mcp_tool";
        }
        if (Character.isDigit(normalized.charAt(0))) {
            return "mcp_" + normalized;
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMapList(Object value) {
        if (!(value instanceof List<?> list)) {
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

    private String safeString(Object value) {
        return Objects.toString(value, "").trim();
    }
}
