package com.mingming.agent.controller;

import com.mingming.agent.service.RunEventQueryService;
import com.mingming.agent.service.RunMetricsService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RunsController {

    private final RunEventQueryService runEventQueryService;
    private final RunMetricsService runMetricsService;

    @GetMapping("/api/runs/{runId}/events")
    public List<Map<String, Object>> getRunEvents(@PathVariable UUID runId) {
        return runEventQueryService.getRunEvents(runId);
    }

    @GetMapping("/api/sessions/{sessionId}/events")
    public List<Map<String, Object>> getSessionEvents(@PathVariable UUID sessionId) {
        return runEventQueryService.getSessionEvents(sessionId);
    }

    @GetMapping("/api/metrics/run-events")
    public Map<String, Object> getRunEventMetrics(@RequestParam(name = "hours", required = false) Integer hours) {
        return runMetricsService.getRunEventMetrics(hours);
    }
}
