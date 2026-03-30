package com.mingming.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.RunEventType;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class StructuredPayloadAssembler {

    private static final Set<String> CALC_TOOLS = Set.of("add", "subtract", "multiply", "divide");

    private final ObjectMapper objectMapper;

    public StructuredPayloadAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<ObjectNode> assembleFromToolResult(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Optional.empty();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payloadJson);
        } catch (Exception ignored) {
            return Optional.empty();
        }

        if (!root.isObject()) {
            return Optional.empty();
        }

        String tool = root.path("tool").asText("");
        JsonNode data = root.path("data");
        String generatedAt = OffsetDateTime.now().toString();

        Optional<ObjectNode> weather = buildWeather(tool, root, data, generatedAt);
        if (weather.isPresent()) {
            return weather;
        }

        Optional<ObjectNode> calc = buildCalcResult(tool, data, generatedAt);
        if (calc.isPresent()) {
            return calc;
        }

        return buildToolError(tool, root, data, generatedAt);
    }

    public Optional<ObjectNode> assemble(List<RunEventEntity> events) {
        if (events == null || events.isEmpty()) {
            return Optional.empty();
        }

        return events.stream()
                .filter(event -> event != null && RunEventType.TOOL_RESULT.name().equals(event.getType()))
                .sorted(Comparator.comparing(
                                RunEventEntity::getSeq,
                                Comparator.nullsFirst(Integer::compareTo))
                        .reversed())
                .map(RunEventEntity::getPayload)
                .map(this::assembleFromToolResult)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<ObjectNode> buildWeather(String tool, JsonNode root, JsonNode data, String generatedAt) {
        if (!"get_weather".equals(tool) || !data.path("ok").asBoolean(false)) {
            return Optional.empty();
        }

        ObjectNode weatherData = objectMapper.createObjectNode();
        weatherData.put("city", data.path("city").asText(""));
        weatherData.put("condition", firstNonBlank(data.path("condition").asText(null), data.path("weather").asText(null), ""));
        weatherData.put("tempC", readDouble(data, "tempC", "temperature"));
        weatherData.put("feelsLikeC", readDouble(data, "feelsLikeC", "tempC", "temperature"));
        weatherData.put("humidity", readDouble(data, "humidity"));
        weatherData.put("windKph", readDouble(data, "windKph", "windSpeedKph", "windPower"));

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("toolName", tool);
        meta.put("source", firstNonBlank(root.path("source").asText(null), data.path("source").asText(null), "unknown"));
        meta.put("generatedAt", generatedAt);
        return Optional.of(buildEnvelope("weather", weatherData, meta));
    }

    private Optional<ObjectNode> buildCalcResult(String tool, JsonNode data, String generatedAt) {
        if (!CALC_TOOLS.contains(tool) || !data.isObject()) {
            return Optional.empty();
        }

        JsonNode result = data.get("result");
        if (result == null || result.isNull()) {
            return Optional.empty();
        }

        ObjectNode calcData = objectMapper.createObjectNode();
        calcData.put("expression", firstNonBlank(data.path("expression").asText(null), tool));
        calcData.set("result", result.deepCopy());
        if (data.has("unit") && !data.get("unit").isNull()) {
            calcData.set("unit", data.get("unit").deepCopy());
        } else {
            calcData.putNull("unit");
        }

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("toolName", tool);
        meta.put("generatedAt", generatedAt);
        return Optional.of(buildEnvelope("calc_result", calcData, meta));
    }

    private Optional<ObjectNode> buildToolError(String tool, JsonNode root, JsonNode data, String generatedAt) {
        String message = firstNonBlank(data.path("error").asText(null), root.path("error").asText(null));
        if ((message == null || message.isBlank()) && data.isObject() && data.has("ok") && !data.path("ok").asBoolean(true)) {
            message = "tool execution failed";
        }
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        ObjectNode errorData = objectMapper.createObjectNode();
        errorData.put("toolName", tool == null || tool.isBlank() ? "unknown" : tool);
        boolean timeoutLike = isTimeoutLike(message);
        if (timeoutLike) {
            errorData.put("category", "UPSTREAM_TIMEOUT");
        } else {
            errorData.put("category", firstNonBlank(data.path("category").asText(null), root.path("category").asText(null), "UPSTREAM_ERROR"));
        }
        errorData.put("message", message);
        boolean explicitRetryable = readBoolean(data, root, "retryable");
        errorData.put("retryable", timeoutLike || explicitRetryable);

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("generatedAt", generatedAt);
        return Optional.of(buildEnvelope("tool_error", errorData, meta));
    }

    private ObjectNode buildEnvelope(String type, ObjectNode data, ObjectNode meta) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("type", type);
        envelope.put("version", "v1");
        envelope.set("data", data);
        envelope.set("meta", meta);
        return envelope;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private double readDouble(JsonNode node, String... candidates) {
        if (node == null || candidates == null) {
            return 0d;
        }
        for (String candidate : candidates) {
            JsonNode value = node.get(candidate);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asDouble();
            }
            if (value.isTextual()) {
                try {
                    return Double.parseDouble(value.asText());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0d;
    }

    private boolean readBoolean(JsonNode primary, JsonNode secondary, String field) {
        if (primary != null) {
            JsonNode primaryValue = primary.get(field);
            if (primaryValue != null && !primaryValue.isNull()) {
                return primaryValue.asBoolean(false);
            }
        }
        if (secondary != null) {
            JsonNode secondaryValue = secondary.get(field);
            if (secondaryValue != null && !secondaryValue.isNull()) {
                return secondaryValue.asBoolean(false);
            }
        }
        return false;
    }

    private boolean isTimeoutLike(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lowered = message.toLowerCase();
        return lowered.contains("timeout")
                || lowered.contains("timed out")
                || lowered.contains("time out")
                || lowered.contains("deadline")
                || lowered.contains("超时");
    }
}
