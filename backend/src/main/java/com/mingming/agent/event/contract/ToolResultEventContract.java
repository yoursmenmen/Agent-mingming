package com.mingming.agent.event.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ToolResultEventContract implements RunEventContract {

    @Override
    public RunEventType eventType() {
        return RunEventType.TOOL_RESULT;
    }

    @Override
    public ObjectNode normalize(ObjectNode payload) {
        payload.put("schemaVersion", "tool-result.v1");
        String tool = payload.path("tool").asText("").trim();
        payload.put("tool", tool.isBlank() ? "unknown" : tool);

        ObjectNode data = payload.path("data").isObject()
                ? (ObjectNode) payload.path("data")
                : payload.objectNode();

        String status = normalizeStatus(data.path("status").asText(""), data.path("ok"));
        data.put("status", status);

        if (!data.has("ok") || data.path("ok").isNull()) {
            data.put("ok", deriveOk(status));
        }

        payload.set("data", data);
        return payload;
    }

    @Override
    public List<String> validate(ObjectNode payload) {
        List<String> errors = new ArrayList<>();
        if (!payload.hasNonNull("tool") || payload.path("tool").asText("").isBlank()) {
            errors.add("tool is required");
        }
        if (!payload.path("data").isObject()) {
            errors.add("data must be object");
            return errors;
        }
        ObjectNode data = (ObjectNode) payload.path("data");
        String status = data.path("status").asText("UNKNOWN");
        if (!List.of("SUCCESS", "FAILED", "PENDING_CONFIRMATION", "BLOCKED_POLICY", "UNKNOWN").contains(status)) {
            errors.add("data.status invalid: " + status);
        }
        if (!data.has("ok")) {
            errors.add("data.ok is required");
        }
        return errors;
    }

    private String normalizeStatus(String status, JsonNode okNode) {
        String candidate = status == null ? "" : status.trim().toUpperCase();
        if (List.of("SUCCESS", "FAILED", "PENDING_CONFIRMATION", "BLOCKED_POLICY").contains(candidate)) {
            return candidate;
        }
        if (okNode != null && okNode.isBoolean()) {
            return okNode.asBoolean(false) ? "SUCCESS" : "FAILED";
        }
        return "UNKNOWN";
    }

    private boolean deriveOk(String status) {
        return "SUCCESS".equals(status) || "PENDING_CONFIRMATION".equals(status);
    }
}
