package com.mingming.agent.event.contract;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RetrievalResultEventContract implements RunEventContract {

    @Override
    public RunEventType eventType() {
        return RunEventType.RETRIEVAL_RESULT;
    }

    @Override
    public ObjectNode normalize(ObjectNode payload) {
        payload.put("schemaVersion", "retrieval-result.v1");
        payload.put("query", payload.path("query").asText(""));
        payload.put("strategy", normalizeStrategy(payload.path("strategy").asText("")));

        ArrayNode hits = payload.path("hits").isArray() ? (ArrayNode) payload.path("hits") : payload.arrayNode();

        int hitCount = hits.size();
        payload.put("vectorHitCount", normalizeCount(payload.path("vectorHitCount").asInt(-1), hitCount));
        payload.put("bm25HitCount", normalizeCount(payload.path("bm25HitCount").asInt(-1), hitCount));
        payload.put("finalHitCount", normalizeCount(payload.path("finalHitCount").asInt(-1), hitCount));
        payload.put("hitCount", hitCount);
        payload.set("hits", hits);
        return payload;
    }

    @Override
    public List<String> validate(ObjectNode payload) {
        List<String> errors = new ArrayList<>();
        if (!payload.has("query")) {
            errors.add("query is required");
        }
        if (!payload.has("strategy")) {
            errors.add("strategy is required");
        }
        if (!payload.has("vectorHitCount")) {
            errors.add("vectorHitCount is required");
        }
        if (!payload.has("bm25HitCount")) {
            errors.add("bm25HitCount is required");
        }
        if (!payload.has("finalHitCount")) {
            errors.add("finalHitCount is required");
        }
        if (!payload.path("hits").isArray()) {
            errors.add("hits must be array");
        }
        return errors;
    }

    private int normalizeCount(int value, int fallback) {
        if (value < 0) {
            return fallback;
        }
        return value;
    }

    private String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return "hybrid";
        }
        return strategy.trim().toLowerCase();
    }
}
