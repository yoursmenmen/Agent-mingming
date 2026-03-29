package com.mingming.agent.controller;

import com.mingming.agent.repository.AgentRunRepository;
import com.mingming.agent.repository.RunEventRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RunsController {

    private final RunEventRepository runEventRepository;
    private final AgentRunRepository agentRunRepository;

    @GetMapping("/api/runs/{runId}/events")
    public List<Map<String, Object>> getRunEvents(@PathVariable UUID runId) {
        return runEventRepository.findByRunIdOrderBySeqAsc(runId).stream()
                .map(e -> Map.<String, Object>of(
                        "id", e.getId(),
                        "runId", e.getRunId(),
                        "seq", e.getSeq(),
                        "createdAt", e.getCreatedAt(),
                        "type", e.getType(),
                        "payload", e.getPayload()
                ))
                .toList();
    }

    @GetMapping("/api/sessions/{sessionId}/events")
    public List<Map<String, Object>> getSessionEvents(@PathVariable UUID sessionId) {
        List<UUID> runIds = agentRunRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(run -> run.getId())
                .toList();

        if (runIds.isEmpty()) {
            return List.of();
        }

        return runEventRepository.findByRunIdInOrderByCreatedAtAscSeqAsc(runIds).stream()
                .map(e -> Map.<String, Object>of(
                        "id", e.getId(),
                        "runId", e.getRunId(),
                        "seq", e.getSeq(),
                        "createdAt", e.getCreatedAt(),
                        "type", e.getType(),
                        "payload", e.getPayload()
                ))
                .toList();
    }
}
