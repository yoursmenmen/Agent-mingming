package com.mingming.agent.event.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoopEventContractsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void terminatedEvent_shouldNotWarn_whenPayloadIsValid() {
        EventContractRegistry registry = new EventContractRegistry(List.of(new LoopTerminatedEventContract()));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reason", "completed");
        payload.put("turnIndex", 2);
        payload.put("elapsedMs", 120L);

        ObjectNode normalized = registry.normalizeAndValidate(RunEventType.LOOP_TERMINATED, payload);

        assertThat(normalized.has("contractWarnings")).isFalse();
    }

    @Test
    void terminatedEvent_shouldWarn_whenTurnIndexOrElapsedIsInvalid() {
        EventContractRegistry registry = new EventContractRegistry(List.of(new LoopTerminatedEventContract()));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reason", "completed");
        payload.put("turnIndex", -2);
        payload.put("elapsedMs", -120L);

        ObjectNode normalized = registry.normalizeAndValidate(RunEventType.LOOP_TERMINATED, payload);

        assertThat(normalized.path("contractWarnings").asText()).contains("turnIndex must be >= 0");
        assertThat(normalized.path("contractWarnings").asText()).contains("elapsedMs must be >= 0");
    }

    @Test
    void startedEvent_shouldNotWarn_whenPayloadIsValid() {
        EventContractRegistry registry = new EventContractRegistry(List.of(new LoopTurnStartedEventContract()));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("turnIndex", 3);
        payload.put("elapsedMs", 1L);

        ObjectNode normalized = registry.normalizeAndValidate(RunEventType.LOOP_TURN_STARTED, payload);

        assertThat(normalized.path("schemaVersion").asText()).isEqualTo("loop-turn-started.v1");
        assertThat(normalized.path("turnIndex").asInt()).isEqualTo(3);
        assertThat(normalized.path("elapsedMs").asLong()).isEqualTo(1L);
        assertThat(normalized.has("contractWarnings")).isFalse();
    }

    @Test
    void startedEvent_shouldWarn_whenTurnIndexOrElapsedIsInvalid() {
        EventContractRegistry registry = new EventContractRegistry(List.of(new LoopTurnStartedEventContract()));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("turnIndex", -3);
        payload.put("elapsedMs", -1L);

        ObjectNode normalized = registry.normalizeAndValidate(RunEventType.LOOP_TURN_STARTED, payload);

        assertThat(normalized.path("schemaVersion").asText()).isEqualTo("loop-turn-started.v1");
        assertThat(normalized.path("turnIndex").asInt()).isEqualTo(1);
        assertThat(normalized.path("elapsedMs").asLong()).isZero();
        assertThat(normalized.path("contractWarnings").asText()).contains("turnIndex must be >= 1");
        assertThat(normalized.path("contractWarnings").asText()).contains("elapsedMs must be >= 0");
    }

    @Test
    void finishedEvent_shouldNotWarn_whenPayloadIsValid() {
        EventContractRegistry registry = new EventContractRegistry(List.of(new LoopTurnFinishedEventContract()));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("turnIndex", 2);
        payload.put("elapsedMs", 8L);
        payload.put("consecutiveToolFailures", 1);

        ObjectNode normalized = registry.normalizeAndValidate(RunEventType.LOOP_TURN_FINISHED, payload);

        assertThat(normalized.path("schemaVersion").asText()).isEqualTo("loop-turn-finished.v1");
        assertThat(normalized.path("turnIndex").asInt()).isEqualTo(2);
        assertThat(normalized.path("elapsedMs").asLong()).isEqualTo(8L);
        assertThat(normalized.path("finalAnswerReady").asBoolean()).isFalse();
        assertThat(normalized.path("toolFailure").asBoolean()).isFalse();
        assertThat(normalized.path("consecutiveToolFailures").asInt()).isEqualTo(1);
        assertThat(normalized.has("contractWarnings")).isFalse();
    }

    @Test
    void finishedEvent_shouldWarn_whenTurnIndexOrElapsedIsInvalid() {
        EventContractRegistry registry = new EventContractRegistry(List.of(new LoopTurnFinishedEventContract()));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("turnIndex", -9);
        payload.put("elapsedMs", -8L);

        ObjectNode normalized = registry.normalizeAndValidate(RunEventType.LOOP_TURN_FINISHED, payload);

        assertThat(normalized.path("schemaVersion").asText()).isEqualTo("loop-turn-finished.v1");
        assertThat(normalized.path("turnIndex").asInt()).isEqualTo(1);
        assertThat(normalized.path("elapsedMs").asLong()).isZero();
        assertThat(normalized.path("finalAnswerReady").asBoolean()).isFalse();
        assertThat(normalized.path("toolFailure").asBoolean()).isFalse();
        assertThat(normalized.path("consecutiveToolFailures").asInt()).isZero();
        assertThat(normalized.path("contractWarnings").asText()).contains("turnIndex must be >= 1");
        assertThat(normalized.path("contractWarnings").asText()).contains("elapsedMs must be >= 0");
    }
}
