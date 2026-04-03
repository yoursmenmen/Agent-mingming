package com.mingming.agent.controller;

import com.mingming.agent.mcp.McpToolService;
import com.mingming.agent.mcp.McpOnboardingService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpToolService mcpToolService;
    private final McpOnboardingService mcpOnboardingService;

    @GetMapping("/api/mcp/servers")
    public Object listServers() {
        log.info("MCP API called: /api/mcp/servers");
        return mcpToolService.listServersWithTools();
    }

    @GetMapping("/api/mcp/tools")
    public Object listTools() {
        log.info("MCP API called: /api/mcp/tools");
        return mcpToolService.listTools();
    }

    @PostMapping("/api/mcp/tools/call")
    public Object callTool(@RequestBody McpToolCallRequest request) {
        log.info("MCP API called: /api/mcp/tools/call, server={}, tool={}", request.server(), request.toolName());
        return mcpToolService.callTool(request.server(), request.toolName(), request.arguments(), "api:mcp-tools-call");
    }

    @PostMapping("/api/mcp/servers/enabled")
    public Object setServerEnabled(@RequestBody McpServerEnabledRequest request) {
        log.info("MCP API called: /api/mcp/servers/enabled, server={}, enabled={}", request.server(), request.enabled());
        return mcpToolService.setServerEnabled(request.server(), request.enabled());
    }

    @GetMapping("/api/mcp/actions/pending")
    public Object listPendingActions() {
        log.info("MCP API called: /api/mcp/actions/pending");
        return mcpToolService.listPendingActions();
    }

    @PostMapping("/api/mcp/actions/{actionId}/confirm")
    public Object confirmPendingAction(@PathVariable String actionId) {
        log.info("MCP API called: /api/mcp/actions/{}/confirm", actionId);
        try {
            return mcpToolService.confirmPendingAction(actionId);
        } catch (RuntimeException ex) {
            return Map.of(
                    "actionId", actionId,
                    "status", "CONFIRM_FAILED",
                    "ok", false,
                    "error", ex.getMessage());
        }
    }

    @PostMapping("/api/mcp/actions/{actionId}/reject")
    public Object rejectPendingAction(@PathVariable String actionId) {
        log.info("MCP API called: /api/mcp/actions/{}/reject", actionId);
        try {
            return mcpToolService.rejectPendingAction(actionId);
        } catch (RuntimeException ex) {
            return Map.of(
                    "actionId", actionId,
                    "status", "REJECT_FAILED",
                    "ok", false,
                    "error", ex.getMessage());
        }
    }

    @PostMapping("/api/mcp/onboarding/plan")
    public Object createOnboardingPlan(@RequestBody McpOnboardingPlanRequest request) {
        log.info("MCP API called: /api/mcp/onboarding/plan, repoUrl={}", request.repoUrl());
        return mcpOnboardingService.createPlan(request.repoUrl(), request.serverName(), request.preferredTransport());
    }

    @PostMapping("/api/mcp/onboarding/apply")
    public Object applyOnboardingPlan(@RequestBody McpOnboardingApplyRequest request) {
        log.info("MCP API called: /api/mcp/onboarding/apply, repoUrl={}, runInstall={}", request.repoUrl(), request.runInstall());
        return mcpOnboardingService.applyPlan(
                request.repoUrl(),
                request.serverName(),
                request.preferredTransport(),
                request.runInstall());
    }

    public record McpToolCallRequest(String server, String toolName, Map<String, Object> arguments) {}

    public record McpServerEnabledRequest(String server, boolean enabled) {}

    public record McpOnboardingPlanRequest(String repoUrl, String serverName, String preferredTransport) {}

    public record McpOnboardingApplyRequest(String repoUrl, String serverName, String preferredTransport, boolean runInstall) {}
}
