package com.mingming.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public class RunEventFactory {

    private final ObjectMapper objectMapper;

    public RunEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RunEvent modelDelta(UUID runId, int seq, String delta) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("delta", delta);
        return new RunEvent(UUID.randomUUID(), runId, seq, OffsetDateTime.now(), RunEventType.MODEL_DELTA, payload);
    }

    public RunEvent error(UUID runId, int seq, String message) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", message);
        return new RunEvent(UUID.randomUUID(), runId, seq, OffsetDateTime.now(), RunEventType.ERROR, payload);
    }
}
