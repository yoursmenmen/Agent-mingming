package com.mingming.agent.api;

import com.mingming.agent.store.RunEventRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RunsController {

    private final RunEventRepository runEventRepository;

    public RunsController(RunEventRepository runEventRepository) {
        this.runEventRepository = runEventRepository;
    }

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
}
