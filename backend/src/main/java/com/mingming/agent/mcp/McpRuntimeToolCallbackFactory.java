package com.mingming.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class McpRuntimeToolCallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(McpRuntimeToolCallbackFactory.class);

    private final McpToolService mcpToolService;
    private final ObjectMapper objectMapper;
    private final boolean runtimeEnabled;
    private final String allowToolsRaw;
    private final String denyToolsRaw;
    private final int maxCallbacks;

    public McpRuntimeToolCallbackFactory(
            McpToolService mcpToolService,
            ObjectMapper objectMapper,
            @Value("${agent.mcp.runtime.enabled:true}") boolean runtimeEnabled,
            @Value("${agent.mcp.runtime.allow-tools:}") String allowToolsRaw,
            @Value("${agent.mcp.runtime.deny-tools:run_local_command}") String denyToolsRaw,
            @Value("${agent.mcp.runtime.max-callbacks:32}") int maxCallbacks) {
        this.mcpToolService = mcpToolService;
        this.objectMapper = objectMapper;
        this.runtimeEnabled = runtimeEnabled;
        this.allowToolsRaw = allowToolsRaw;
        this.denyToolsRaw = denyToolsRaw;
        this.maxCallbacks = maxCallbacks <= 0 ? 32 : maxCallbacks;
    }

    public record RuntimeToolBundle(
            List<ToolCallback> callbacks,
            List<Map<String, Object>> boundTools,
            List<Map<String, Object>> blockedTools,
            List<Map<String, Object>> discoveryErrors) {}

    public List<ToolCallback> createCallbacks() {
        return prepareRuntimeTools().callbacks();
    }

    public RuntimeToolBundle prepareRuntimeTools() {
        if (!runtimeEnabled) {
            return new RuntimeToolBundle(List.of(), List.of(), List.of(), List.of());
        }

        Set<String> allowTools = parseRules(allowToolsRaw);
        Set<String> denyTools = parseRules(denyToolsRaw);
        try {
            Map<String, Object> toolDiscovery = mcpToolService.listTools();
            List<Map<String, Object>> discoveredTools = toMapList(toolDiscovery.get("tools"));
            List<Map<String, Object>> discoveryErrors = toMapList(toolDiscovery.get("errors"));
            if (discoveredTools.isEmpty()) {
                return new RuntimeToolBundle(List.of(), List.of(), List.of(), List.copyOf(discoveryErrors));
            }

            List<ToolCallback> callbacks = new ArrayList<>();
            List<Map<String, Object>> boundTools = new ArrayList<>();
            List<Map<String, Object>> blockedTools = new ArrayList<>();
            Set<String> usedNames = new LinkedHashSet<>();
            for (Map<String, Object> discoveredTool : discoveredTools) {
                if (callbacks.size() >= maxCallbacks) {
                    blockedTools.add(Map.of(
                            "server", safeString(discoveredTool.get("server")),
                            "tool", safeString(discoveredTool.get("name")),
                            "reason", "max-callbacks-exceeded"));
                    continue;
                }

                String server = safeString(discoveredTool.get("server"));
                String originalToolName = safeString(discoveredTool.get("name"));
                if (server.isBlank() || originalToolName.isBlank()) {
                    continue;
                }

                String policyReason = matchPolicyReason(server, originalToolName, allowTools, denyTools);
                if (policyReason != null) {
                    blockedTools.add(Map.of(
                            "server", server,
                            "tool", originalToolName,
                            "reason", policyReason));
                    continue;
                }

                List<String> requiredFields = extractRequiredFields(discoveredTool.get("inputSchema"));
                String callbackName = uniqueName(buildToolName(server, originalToolName), usedNames);
                String description = buildDescription(
                        server,
                        originalToolName,
                        safeString(discoveredTool.get("description")),
                        requiredFields);
                Function<Map<String, Object>, Map<String, Object>> handler =
                        args -> executeTool(server, originalToolName, args, requiredFields);

                callbacks.add(FunctionToolCallback.builder(callbackName, handler)
                        .description(description)
                        .inputType(Map.class)
                        .build());

                Map<String, Object> bound = new LinkedHashMap<>();
                bound.put("callbackName", callbackName);
                bound.put("server", server);
                bound.put("tool", originalToolName);
                bound.put("required", requiredFields);
                boundTools.add(bound);
            }

            log.info(
                    "MCP runtime tool callbacks ready: count={}, blocked={}, discoveryErrors={}",
                    callbacks.size(),
                    blockedTools.size(),
                    discoveryErrors.size());
            return new RuntimeToolBundle(
                    List.copyOf(callbacks),
                    List.copyOf(boundTools),
                    List.copyOf(blockedTools),
                    List.copyOf(discoveryErrors));
        } catch (RuntimeException ex) {
            log.warn("MCP runtime tool callbacks unavailable: message={}", ex.getMessage());
            return new RuntimeToolBundle(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(Map.of("message", safeString(ex.getMessage()))));
        }
    }

    private Map<String, Object> executeTool(
            String server,
            String toolName,
            Map<String, Object> args,
            List<String> requiredFields) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        List<String> missing = missingRequiredFields(safeArgs, requiredFields);
        if (!missing.isEmpty()) {
            return Map.of(
                    "ok", false,
                    "category", "VALIDATION_ERROR",
                    "error", "missing required args: " + String.join(", ", missing),
                    "missing", missing,
                    "server", server,
                    "tool", toolName);
        }
        Map<String, Object> response = mcpToolService.callTool(server, toolName, safeArgs, "chat:runtime-callback");
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

    private String buildDescription(
            String server,
            String originalToolName,
            String originalDescription,
            List<String> requiredFields) {
        StringBuilder builder = new StringBuilder();
        builder.append("Call MCP tool '")
                .append(originalToolName)
                .append("' on server '")
                .append(server)
                .append("'.");
        if (!originalDescription.isBlank()) {
            builder.append(' ').append(originalDescription);
        }
        if (!requiredFields.isEmpty()) {
            builder.append(" Required args: ").append(String.join(", ", requiredFields)).append('.');
        }
        return builder.toString();
    }

    private List<String> extractRequiredFields(Object inputSchema) {
        if (inputSchema == null) {
            return List.of();
        }
        if (inputSchema instanceof Map<?, ?> schemaMap) {
            return toStringList(schemaMap.get("required"));
        }
        if (inputSchema instanceof String schemaText && !schemaText.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(schemaText);
                JsonNode requiredNode = root.path("required");
                if (!requiredNode.isArray()) {
                    return List.of();
                }
                List<String> required = new ArrayList<>();
                for (JsonNode node : requiredNode) {
                    if (node.isTextual() && !node.asText().isBlank()) {
                        required.add(node.asText().trim());
                    }
                }
                return List.copyOf(required);
            } catch (Exception ignored) {
                return List.of();
            }
        }
        return List.of();
    }

    private List<String> missingRequiredFields(Map<String, Object> args, List<String> requiredFields) {
        if (requiredFields == null || requiredFields.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String field : requiredFields) {
            Object value = args.get(field);
            if (value == null) {
                missing.add(field);
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                missing.add(field);
            }
        }
        return List.copyOf(missing);
    }

    private String matchPolicyReason(String server, String toolName, Set<String> allowTools, Set<String> denyTools) {
        String globalKey = normalizeRule(toolName);
        String scopedKey = normalizeRule(server + ":" + toolName);
        if (denyTools.contains(globalKey) || denyTools.contains(scopedKey)) {
            return "denied-by-policy";
        }
        if (!allowTools.isEmpty() && !(allowTools.contains(globalKey) || allowTools.contains(scopedKey))) {
            return "not-in-allowlist";
        }
        return null;
    }

    private Set<String> parseRules(String rawRules) {
        if (rawRules == null || rawRules.isBlank()) {
            return Set.of();
        }
        Set<String> rules = new LinkedHashSet<>();
        for (String token : rawRules.split(",")) {
            String normalized = normalizeRule(token);
            if (!normalized.isBlank()) {
                rules.add(normalized);
            }
        }
        return Set.copyOf(rules);
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                out.add(text.trim());
            }
        }
        return List.copyOf(out);
    }

    private String normalizeRule(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
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
