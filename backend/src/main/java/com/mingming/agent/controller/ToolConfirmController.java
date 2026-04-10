package com.mingming.agent.controller;

import com.mingming.agent.react.tool.ToolConfirmRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ToolConfirmController {

    private final ToolConfirmRegistry toolConfirmRegistry;

    public record ToolConfirmRequest(String toolCallId, boolean approved) {}
    public record ToolConfirmResponse(String toolCallId, boolean resolved) {}

    @PostMapping("/api/runs/{runId}/tool-confirm")
    public ResponseEntity<ToolConfirmResponse> confirm(
            @PathVariable String runId,
            @RequestBody ToolConfirmRequest req) {
        if (req.toolCallId() == null || req.toolCallId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        toolConfirmRegistry.resolve(req.toolCallId(), req.approved());
        return ResponseEntity.ok(new ToolConfirmResponse(req.toolCallId(), true));
    }
}
