package com.mingming.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.repository.RunEventRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RunMetricsService {

    private static final int DEFAULT_WINDOW_HOURS = 24;
    private static final int MIN_WINDOW_HOURS = 1;
    private static final int MAX_WINDOW_HOURS = 168;

    private final RunEventRepository runEventRepository;
    private final ObjectMapper objectMapper;

    public Map<String, Object> getRunEventMetrics(Integer hours) {
        int windowHours = normalizeWindowHours(hours);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = now.minusHours(windowHours);

        List<RunEventEntity> events = runEventRepository.findByTypeInAndCreatedAtAfter(
                List.of("TOOL_CALL", "TOOL_RESULT", "MCP_CONFIRM_RESULT"), from);

        int toolCallTotal = 0;
        int toolResultTotal = 0;
        int toolErrorTotal = 0;
        int confirmTotal = 0;
        int confirmSuccessTotal = 0;
        int confirmFailedTotal = 0;
        int confirmRejectedTotal = 0;

        for (RunEventEntity event : events) {
            if (event == null || event.getType() == null) {
                continue;
            }
            switch (event.getType()) {
                case "TOOL_CALL" -> toolCallTotal++;
                case "TOOL_RESULT" -> {
                    toolResultTotal++;
                    if (isToolResultError(event.getPayload())) {
                        toolErrorTotal++;
                    }
                }
                case "MCP_CONFIRM_RESULT" -> {
                    confirmTotal++;
                    String status = readConfirmStatus(event.getPayload());
                    switch (status) {
                        case "CONFIRMED_EXECUTED" -> confirmSuccessTotal++;
                        case "CONFIRM_EXECUTION_FAILED" -> confirmFailedTotal++;
                        case "REJECTED" -> confirmRejectedTotal++;
                        default -> {
                            // keep total only
                        }
                    }
                }
                default -> {
                    // ignore other event types
                }
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("windowHours", windowHours);
        payload.put("from", from);
        payload.put("to", now);
        payload.put("tool_call_total", toolCallTotal);
        payload.put("tool_result_total", toolResultTotal);
        payload.put("tool_error_total", toolErrorTotal);
        payload.put("confirm_total", confirmTotal);
        payload.put("confirm_success_total", confirmSuccessTotal);
        payload.put("confirm_failed_total", confirmFailedTotal);
        payload.put("confirm_rejected_total", confirmRejectedTotal);
        return payload;
    }

    private int normalizeWindowHours(Integer hours) {
        if (hours == null) {
            return DEFAULT_WINDOW_HOURS;
        }
        if (hours < MIN_WINDOW_HOURS) {
            return MIN_WINDOW_HOURS;
        }
        if (hours > MAX_WINDOW_HOURS) {
            return MAX_WINDOW_HOURS;
        }
        return hours;
    }

    private boolean isToolResultError(String payloadText) {
        JsonNode root = readJson(payloadText);
        if (root == null || !root.isObject()) {
            return true;
        }
        JsonNode data = root.path("data");
        if (!data.isObject()) {
            return true;
        }
        if (data.has("ok") && !data.path("ok").asBoolean(false)) {
            return true;
        }
        String status = data.path("status").asText("UNKNOWN");
        return "FAILED".equals(status) || "BLOCKED_POLICY".equals(status) || "CONFIRM_EXECUTION_FAILED".equals(status);
    }

    private String readConfirmStatus(String payloadText) {
        JsonNode root = readJson(payloadText);
        if (root == null || !root.isObject()) {
            return "UNKNOWN";
        }
        String status = root.path("status").asText("UNKNOWN");
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        return status;
    }

    private JsonNode readJson(String payloadText) {
        if (payloadText == null || payloadText.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payloadText);
        } catch (Exception ignored) {
            return null;
        }
    }
}
