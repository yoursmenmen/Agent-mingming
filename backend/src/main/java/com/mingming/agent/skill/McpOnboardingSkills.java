package com.mingming.agent.skill;

import com.mingming.agent.mcp.McpOnboardingService;
import com.mingming.agent.tool.LocalToolProvider;
import com.mingming.agent.tool.ToolEventService;
import com.mingming.agent.tool.ToolMetadata;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class McpOnboardingSkills implements LocalToolProvider {

    private final McpOnboardingService mcpOnboardingService;
    private final ToolEventService toolEventService;

    @Tool(name = "mcp_onboarding_plan", description = "Create MCP onboarding plan from repository URL")
    public Map<String, Object> onboardingPlan(String repoUrl, String serverName, String preferredTransport, ToolContext toolContext) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("repoUrl", repoUrl == null ? "" : repoUrl);
        if (serverName != null && !serverName.isBlank()) {
            args.put("serverName", serverName);
        }
        if (preferredTransport != null && !preferredTransport.isBlank()) {
            args.put("preferredTransport", preferredTransport);
        }

        toolEventService.recordToolCall(toolContext, "mcp_onboarding_plan", args);
        try {
            Map<String, Object> result = mcpOnboardingService.createPlan(repoUrl, serverName, preferredTransport);
            toolEventService.recordToolResult(toolContext, "mcp_onboarding_plan", result);
            return result;
        } catch (RuntimeException ex) {
            Map<String, Object> failure = Map.of(
                    "ok", false,
                    "error", ex.getMessage(),
                    "status", "PLAN_FAILED");
            toolEventService.recordToolResult(toolContext, "mcp_onboarding_plan", failure);
            return failure;
        }
    }

    @Tool(name = "mcp_onboarding_apply", description = "Apply MCP onboarding plan after explicit user approval")
    public Map<String, Object> onboardingApply(
            String repoUrl,
            String serverName,
            String preferredTransport,
            Boolean runInstall,
            Boolean approved,
            ToolContext toolContext) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("repoUrl", repoUrl == null ? "" : repoUrl);
        args.put("runInstall", runInstall != null && runInstall);
        args.put("approved", approved != null && approved);
        if (serverName != null && !serverName.isBlank()) {
            args.put("serverName", serverName);
        }
        if (preferredTransport != null && !preferredTransport.isBlank()) {
            args.put("preferredTransport", preferredTransport);
        }

        toolEventService.recordToolCall(toolContext, "mcp_onboarding_apply", args);

        if (approved == null || !approved) {
            Map<String, Object> waiting = Map.of(
                    "ok", false,
                    "status", "WAITING_USER_APPROVAL",
                    "message", "apply requires explicit approval=true");
            toolEventService.recordToolResult(toolContext, "mcp_onboarding_apply", waiting);
            return waiting;
        }

        try {
            Map<String, Object> result = mcpOnboardingService.applyPlan(
                    repoUrl,
                    serverName,
                    preferredTransport,
                    runInstall != null && runInstall);
            toolEventService.recordToolResult(toolContext, "mcp_onboarding_apply", result);
            return result;
        } catch (RuntimeException ex) {
            Map<String, Object> failure = Map.of(
                    "ok", false,
                    "error", ex.getMessage(),
                    "status", "APPLY_FAILED");
            toolEventService.recordToolResult(toolContext, "mcp_onboarding_apply", failure);
            return failure;
        }
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "mcp-onboarding",
                "Generate and apply MCP onboarding plan from repository URL",
                "mcp");
    }
}
