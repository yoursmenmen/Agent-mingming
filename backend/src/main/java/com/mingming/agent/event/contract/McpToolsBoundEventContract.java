package com.mingming.agent.event.contract;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class McpToolsBoundEventContract implements RunEventContract {

    @Override
    public RunEventType eventType() {
        return RunEventType.MCP_TOOLS_BOUND;
    }

    @Override
    public ObjectNode normalize(ObjectNode payload) {
        payload.put("schemaVersion", "mcp-tools-bound.v1");

        int localToolCount = normalizeCount(payload.path("localToolCount").asInt(-1));
        int mcpToolCount = normalizeCount(payload.path("mcpToolCount").asInt(-1));
        int totalToolCount = normalizeCount(payload.path("totalToolCount").asInt(localToolCount + mcpToolCount));

        payload.put("localToolCount", localToolCount);
        payload.put("mcpToolCount", mcpToolCount);
        payload.put("totalToolCount", totalToolCount);

        payload.set("injectedMcpTools", asArray(payload, "injectedMcpTools"));
        payload.set("blockedMcpTools", asArray(payload, "blockedMcpTools"));
        payload.set("mcpDiscoveryErrors", asArray(payload, "mcpDiscoveryErrors"));
        return payload;
    }

    @Override
    public List<String> validate(ObjectNode payload) {
        List<String> errors = new ArrayList<>();
        if (!payload.has("localToolCount")) {
            errors.add("localToolCount is required");
        }
        if (!payload.has("mcpToolCount")) {
            errors.add("mcpToolCount is required");
        }
        if (!payload.has("totalToolCount")) {
            errors.add("totalToolCount is required");
        }
        if (!payload.path("injectedMcpTools").isArray()) {
            errors.add("injectedMcpTools must be array");
        }
        if (!payload.path("blockedMcpTools").isArray()) {
            errors.add("blockedMcpTools must be array");
        }
        if (!payload.path("mcpDiscoveryErrors").isArray()) {
            errors.add("mcpDiscoveryErrors must be array");
        }
        return errors;
    }

    private ArrayNode asArray(ObjectNode payload, String key) {
        return payload.path(key).isArray() ? (ArrayNode) payload.path(key) : payload.arrayNode();
    }

    private int normalizeCount(int value) {
        return Math.max(0, value);
    }
}
