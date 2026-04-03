package com.mingming.agent.event.contract;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RagSyncEventContract implements RunEventContract {

    @Override
    public RunEventType eventType() {
        return RunEventType.RAG_SYNC;
    }

    @Override
    public ObjectNode normalize(ObjectNode payload) {
        payload.put("schemaVersion", "rag-sync.v1");
        payload.put("phase", fallback(payload.path("phase").asText(""), "unknown"));
        payload.put("trigger", fallback(payload.path("trigger").asText(""), "unknown"));
        payload.put("error", payload.path("error").asText(""));

        ObjectNode stats = payload.path("stats").isObject()
                ? (ObjectNode) payload.path("stats")
                : payload.objectNode();
        stats.put("inserted", normalizeCount(stats.path("inserted").asInt(0)));
        stats.put("updated", normalizeCount(stats.path("updated").asInt(0)));
        stats.put("softDeleted", normalizeCount(stats.path("softDeleted").asInt(0)));
        stats.put("unchanged", normalizeCount(stats.path("unchanged").asInt(0)));
        payload.set("stats", stats);
        return payload;
    }

    @Override
    public List<String> validate(ObjectNode payload) {
        List<String> errors = new ArrayList<>();
        if (payload.path("phase").asText("").isBlank()) {
            errors.add("phase is required");
        }
        if (payload.path("trigger").asText("").isBlank()) {
            errors.add("trigger is required");
        }
        if (!payload.path("stats").isObject()) {
            errors.add("stats must be object");
            return errors;
        }
        ObjectNode stats = (ObjectNode) payload.path("stats");
        if (!stats.has("inserted") || !stats.has("updated") || !stats.has("softDeleted") || !stats.has("unchanged")) {
            errors.add("stats fields are incomplete");
        }
        return errors;
    }

    private int normalizeCount(int value) {
        return Math.max(0, value);
    }

    private String fallback(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
