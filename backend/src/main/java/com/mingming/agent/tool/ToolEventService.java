package com.mingming.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.repository.RunEventRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ToolEventService {

    private final ToolRunContextHolder contextHolder;
    private final RunEventRepository runEventRepository;
    private final ObjectMapper objectMapper;

    public void recordToolCall(String toolName, Map<String, Object> args) {
        record(RunEventType.TOOL_CALL, toolName, args);
    }

    public void recordToolResult(String toolName, Object result) {
        record(RunEventType.TOOL_RESULT, toolName, result);
    }

    private void record(RunEventType type, String toolName, Object data) {
        UUID runId = contextHolder.currentRunId();
        Integer seq = contextHolder.nextSeq();
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
}
