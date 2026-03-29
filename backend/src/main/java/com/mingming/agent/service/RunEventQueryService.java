package com.mingming.agent.service;

import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.repository.AgentRunRepository;
import com.mingming.agent.repository.RunEventRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RunEventQueryService {

    private final RunEventRepository runEventRepository;
    private final AgentRunRepository agentRunRepository;

    public List<Map<String, Object>> getRunEvents(UUID runId) {
        return runEventRepository.findByRunIdOrderBySeqAsc(runId).stream()
                .map(this::toMap)
                .toList();
    }

    public List<Map<String, Object>> getSessionEvents(UUID sessionId) {
        List<UUID> runIds = agentRunRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(run -> run.getId())
                .toList();

        if (runIds.isEmpty()) {
            return List.of();
        }

        return runEventRepository.findByRunIdInOrderByCreatedAtAscSeqAsc(runIds).stream()
                .map(this::toMap)
                .toList();
    }

    private Map<String, Object> toMap(RunEventEntity e) {
        return Map.<String, Object>of(
                "id", e.getId(),
                "runId", e.getRunId(),
                "seq", e.getSeq(),
                "createdAt", e.getCreatedAt(),
                "type", e.getType(),
                "payload", e.getPayload());
    }
}
