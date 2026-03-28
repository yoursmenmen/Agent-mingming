package com.mingming.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RunEvent(
        UUID id,
        UUID runId,
        int seq,
        OffsetDateTime createdAt,
        RunEventType type,
        JsonNode payload
) {}
