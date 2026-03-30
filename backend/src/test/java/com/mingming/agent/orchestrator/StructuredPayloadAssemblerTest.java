package com.mingming.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.entity.RunEventEntity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StructuredPayloadAssemblerTest {

    private final StructuredPayloadAssembler assembler = new StructuredPayloadAssembler(new ObjectMapper());

    @Test
    void assembleFromToolResult_shouldBuildWeatherV1Payload() {
        String payloadJson =
                """
                {"tool":"get_weather","source":"amap","data":{"ok":true,"city":"北京","condition":"晴","tempC":26.0,"feelsLikeC":27.0,"humidity":42,"windKph":13.5}}
                """;

        Optional<com.fasterxml.jackson.databind.node.ObjectNode> structured = assembler.assembleFromToolResult(payloadJson);

        assertThat(structured).isPresent();
        assertThat(structured.orElseThrow().path("type").asText()).isEqualTo("weather");
        assertThat(structured.orElseThrow().path("version").asText()).isEqualTo("v1");
        assertThat(structured.orElseThrow().path("data").path("city").asText()).isEqualTo("北京");
        assertThat(structured.orElseThrow().path("data").path("condition").asText()).isEqualTo("晴");
        assertThat(structured.orElseThrow().path("data").path("tempC").asDouble()).isEqualTo(26.0d);
        assertThat(structured.orElseThrow().path("meta").path("toolName").asText()).isEqualTo("get_weather");
        assertThat(structured.orElseThrow().path("meta").path("source").asText()).isEqualTo("amap");
        assertThat(structured.orElseThrow().path("meta").path("generatedAt").asText()).isNotBlank();
    }

    @Test
    void assembleFromToolResult_shouldBuildCalcResultV1Payload() {
        String payloadJson = """
                {"tool":"add","data":{"expression":"1.5 + 2.0","result":3.5,"unit":"number"}}
                """;

        Optional<com.fasterxml.jackson.databind.node.ObjectNode> structured = assembler.assembleFromToolResult(payloadJson);

        assertThat(structured).isPresent();
        assertThat(structured.orElseThrow().path("type").asText()).isEqualTo("calc_result");
        assertThat(structured.orElseThrow().path("version").asText()).isEqualTo("v1");
        assertThat(structured.orElseThrow().path("data").path("expression").asText()).isEqualTo("1.5 + 2.0");
        assertThat(structured.orElseThrow().path("data").path("result").asDouble()).isEqualTo(3.5d);
        assertThat(structured.orElseThrow().path("data").path("unit").asText()).isEqualTo("number");
        assertThat(structured.orElseThrow().path("meta").path("toolName").asText()).isEqualTo("add");
        assertThat(structured.orElseThrow().path("meta").path("generatedAt").asText()).isNotBlank();
    }

    @Test
    void assembleFromToolResult_shouldSetCalcUnitAsJsonNullWhenAbsent() {
        String payloadJson = """
                {"tool":"add","data":{"expression":"1 + 2","result":3}}
                """;

        Optional<com.fasterxml.jackson.databind.node.ObjectNode> structured = assembler.assembleFromToolResult(payloadJson);

        assertThat(structured).isPresent();
        assertThat(structured.orElseThrow().path("type").asText()).isEqualTo("calc_result");
        assertThat(structured.orElseThrow().path("data").get("unit").isNull()).isTrue();
    }

    @Test
    void assembleFromToolResult_shouldBuildToolErrorV1PayloadAsFallback() {
        String payloadJson = """
                {"tool":"get_weather","data":{"ok":false,"error":"city is required","category":"validation","retryable":false}}
                """;

        Optional<com.fasterxml.jackson.databind.node.ObjectNode> structured = assembler.assembleFromToolResult(payloadJson);

        assertThat(structured).isPresent();
        assertThat(structured.orElseThrow().path("type").asText()).isEqualTo("tool_error");
        assertThat(structured.orElseThrow().path("version").asText()).isEqualTo("v1");
        assertThat(structured.orElseThrow().path("data").path("toolName").asText()).isEqualTo("get_weather");
        assertThat(structured.orElseThrow().path("data").path("category").asText()).isEqualTo("validation");
        assertThat(structured.orElseThrow().path("data").path("message").asText()).isEqualTo("city is required");
        assertThat(structured.orElseThrow().path("data").path("retryable").asBoolean()).isFalse();
        assertThat(structured.orElseThrow().path("meta").path("generatedAt").asText()).isNotBlank();
    }

    @Test
    void assembleFromToolResult_shouldMapTimeoutErrorToUpstreamTimeoutRetryable() {
        String payloadJson = """
                {"tool":"get_weather","data":{"ok":false,"error":"request timeout while calling upstream"}}
                """;

        Optional<com.fasterxml.jackson.databind.node.ObjectNode> structured = assembler.assembleFromToolResult(payloadJson);

        assertThat(structured).isPresent();
        assertThat(structured.orElseThrow().path("type").asText()).isEqualTo("tool_error");
        assertThat(structured.orElseThrow().path("data").path("category").asText()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(structured.orElseThrow().path("data").path("retryable").asBoolean()).isTrue();
    }

    @Test
    void assembleFromToolResult_shouldReturnEmptyForInvalidPayloadJson() {
        Optional<com.fasterxml.jackson.databind.node.ObjectNode> structured = assembler.assembleFromToolResult("{invalid-json");

        assertThat(structured).isEmpty();
    }

    @Test
    void assemble_shouldReturnMostRecentValidStructuredPayloadFromToolResultEvents() {
        RunEventEntity nonTool = event("MODEL_MESSAGE", 3, "{\"content\":\"hello\"}");
        RunEventEntity oldValid = event("TOOL_RESULT", 4, "{\"tool\":\"add\",\"data\":{\"expression\":\"1+2\",\"result\":3,\"unit\":\"number\"}}");
        RunEventEntity invalidLatest = event("TOOL_RESULT", 8, "{\"tool\":\"add\",\"data\":{\"result\":null}}");
        RunEventEntity latestValid = event("TOOL_RESULT", 7, "{\"tool\":\"get_weather\",\"source\":\"amap\",\"data\":{\"ok\":true,\"city\":\"上海\",\"condition\":\"多云\",\"tempC\":20.0,\"feelsLikeC\":19.0,\"humidity\":65,\"windKph\":9.5}}");

        Optional<com.fasterxml.jackson.databind.node.ObjectNode> structured =
                assembler.assemble(List.of(nonTool, oldValid, invalidLatest, latestValid));

        assertThat(structured).isPresent();
        assertThat(structured.orElseThrow().path("type").asText()).isEqualTo("weather");
        assertThat(structured.orElseThrow().path("data").path("city").asText()).isEqualTo("上海");
    }

    private RunEventEntity event(String type, int seq, String payload) {
        RunEventEntity entity = new RunEventEntity();
        entity.setType(type);
        entity.setSeq(seq);
        entity.setPayload(payload);
        return entity;
    }
}
