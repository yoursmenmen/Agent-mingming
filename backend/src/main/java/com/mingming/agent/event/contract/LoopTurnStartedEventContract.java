package com.mingming.agent.event.contract;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LoopTurnStartedEventContract implements RunEventContract {

    private static final String CORRECTIONS_FIELD = "_contractCorrections";

    @Override
    public RunEventType eventType() {
        return RunEventType.LOOP_TURN_STARTED;
    }

    @Override
    public ObjectNode normalize(ObjectNode payload) {
        ArrayNode corrections = payload.putArray(CORRECTIONS_FIELD);
        int turnIndex = payload.path("turnIndex").asInt(1);
        if (turnIndex <= 0) {
            corrections.add("turnIndex");
        }
        long elapsedMs = payload.path("elapsedMs").asLong(0L);
        if (elapsedMs < 0L) {
            corrections.add("elapsedMs");
        }

        payload.put("schemaVersion", "loop-turn-started.v1");
        payload.put("turnIndex", Math.max(1, turnIndex));
        payload.put("elapsedMs", Math.max(0L, elapsedMs));
        return payload;
    }

    @Override
    public List<String> validate(ObjectNode payload) {
        List<String> errors = new ArrayList<>();
        if (payload.path("turnIndex").asInt(0) <= 0 || hasCorrection(payload, "turnIndex")) {
            errors.add("turnIndex must be >= 1");
        }
        if (payload.path("elapsedMs").asLong(-1L) < 0L || hasCorrection(payload, "elapsedMs")) {
            errors.add("elapsedMs must be >= 0");
        }
        payload.remove(CORRECTIONS_FIELD);
        return errors;
    }

    private boolean hasCorrection(ObjectNode payload, String field) {
        if (!payload.path(CORRECTIONS_FIELD).isArray()) {
            return false;
        }
        for (var correction : payload.withArray(CORRECTIONS_FIELD)) {
            if (field.equals(correction.asText())) {
                return true;
            }
        }
        return false;
    }
}
