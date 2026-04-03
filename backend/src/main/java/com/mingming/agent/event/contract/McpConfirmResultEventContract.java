package com.mingming.agent.event.contract;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class McpConfirmResultEventContract implements RunEventContract {

    @Override
    public RunEventType eventType() {
        return RunEventType.MCP_CONFIRM_RESULT;
    }

    @Override
    public ObjectNode normalize(ObjectNode payload) {
        payload.put("schemaVersion", "mcp-confirm-result.v1");
        payload.put("actionId", fallback(payload.path("actionId").asText(""), "unknown"));
        payload.put("server", fallback(payload.path("server").asText(""), "unknown"));
        payload.put("tool", fallback(payload.path("tool").asText(""), "unknown"));
        payload.put("source", payload.path("source").asText(""));
        payload.put("reason", payload.path("reason").asText(""));

        String status = normalizeStatus(payload.path("status").asText(""));
        payload.put("status", status);

        ObjectNode result = payload.path("result").isObject()
                ? (ObjectNode) payload.path("result")
                : payload.objectNode();
        if (!result.has("ok")) {
            result.put("ok", "CONFIRMED_EXECUTED".equals(status) || "REJECTED".equals(status));
        }
        if (!result.has("executed")) {
            result.put("executed", "CONFIRMED_EXECUTED".equals(status));
        }
        if (!result.hasNonNull("status") || result.path("status").asText("").isBlank()) {
            result.put("status", status);
        }

        payload.set("result", result);
        return payload;
    }

    @Override
    public List<String> validate(ObjectNode payload) {
        List<String> errors = new ArrayList<>();
        if (payload.path("actionId").asText("").isBlank()) {
            errors.add("actionId is required");
        }
        if (payload.path("server").asText("").isBlank()) {
            errors.add("server is required");
        }
        if (payload.path("tool").asText("").isBlank()) {
            errors.add("tool is required");
        }
        String status = payload.path("status").asText("UNKNOWN");
        if (!List.of("CONFIRMED_EXECUTED", "CONFIRM_EXECUTION_FAILED", "REJECTED", "UNKNOWN").contains(status)) {
            errors.add("status invalid: " + status);
        }
        if (!payload.path("result").isObject()) {
            errors.add("result must be object");
        }
        return errors;
    }

    private String normalizeStatus(String status) {
        String candidate = status == null ? "" : status.trim().toUpperCase();
        return switch (candidate) {
            case "CONFIRMED_EXECUTED", "CONFIRM_EXECUTION_FAILED", "REJECTED" -> candidate;
            default -> "UNKNOWN";
        };
    }

    private String fallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
