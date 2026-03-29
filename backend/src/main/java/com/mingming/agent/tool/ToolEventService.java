package com.mingming.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.repository.RunEventRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ToolEventService {

    private final RunEventRepository runEventRepository;
    private final ObjectMapper objectMapper;

    public void recordToolCall(ToolContext toolContext, String toolName, Map<String, Object> args) {
        record(toolContext, RunEventType.TOOL_CALL, toolName, args);
    }

    public void recordToolResult(ToolContext toolContext, String toolName, Object result) {
        record(toolContext, RunEventType.TOOL_RESULT, toolName, result);
    }

    private void record(ToolContext toolContext, RunEventType type, String toolName, Object data) {
        UUID runId = extractRunId(toolContext);
        Integer seq = nextSeq(toolContext);
        if (runId == null || seq == null) {
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("tool", toolName);
        payload.set("data", objectMapper.valueToTree(data));

        RunEventEntity entity = new RunEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setRunId(runId);
        entity.setSeq(seq);
        entity.setType(type.name());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setPayload(payload.toString());
        runEventRepository.save(entity);
    }

    private UUID extractRunId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object runIdValue = toolContext.getContext().get("runId");
        if (!(runIdValue instanceof String runIdText) || runIdText.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(runIdText);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Integer nextSeq(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object seqCounterValue = toolContext.getContext().get("seqCounter");
        if (!(seqCounterValue instanceof AtomicInteger seqCounter)) {
            return null;
        }
        return seqCounter.getAndIncrement();
    }
}
