package com.mingming.agent.skill;

import com.mingming.agent.mcp.McpToolService;
import com.mingming.agent.tool.LocalToolProvider;
import com.mingming.agent.tool.ToolEventService;
import com.mingming.agent.tool.ToolMetadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class McpBridgeSkills implements LocalToolProvider {

    private final McpToolService mcpToolService;
    private final ToolEventService toolEventService;
    private final String defaultServer;

    public McpBridgeSkills(
            McpToolService mcpToolService,
            ToolEventService toolEventService,
            @Value("${agent.mcp.default-server:local-ops}") String defaultServer) {
        this.mcpToolService = mcpToolService;
        this.toolEventService = toolEventService;
        this.defaultServer = defaultServer;
    }

    @Tool(name = "fetch_page", description = "Fetch page content from URL using MCP tool")
    public Map<String, Object> fetchPage(String url, Integer timeoutSec, Integer maxChars, ToolContext toolContext) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("url", url == null ? "" : url);
        if (timeoutSec != null && timeoutSec > 0) {
            arguments.put("timeoutSec", timeoutSec);
        }
        if (maxChars != null && maxChars > 0) {
            arguments.put("maxChars", maxChars);
        }
        return callMcpTool("fetch_page", arguments, toolContext);
    }

    @Tool(name = "run_local_command", description = "Run local or ssh command using MCP tool")
    public Map<String, Object> runLocalCommand(String command, List<String> args, ToolContext toolContext) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("command", command == null ? "" : command);
        arguments.put("args", args == null ? List.of() : args);
        return callMcpTool("run_local_command", arguments, toolContext);
    }

    @Tool(name = "k8s_cluster_status", description = "Query Kubernetes cluster status via MCP tool")
    public Map<String, Object> k8sClusterStatus(String namespace, String selector, ToolContext toolContext) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (namespace != null && !namespace.isBlank()) {
            arguments.put("namespace", namespace);
        }
        if (selector != null && !selector.isBlank()) {
            arguments.put("selector", selector);
        }
        return callMcpTool("k8s_cluster_status", arguments, toolContext);
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(" local-ops-mcp-bridge-tools ", "Skills support via MCP bridge", "mcp");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callMcpTool(String toolName, Map<String, Object> arguments, ToolContext toolContext) {
        toolEventService.recordToolCall(toolContext, toolName, arguments);
        try {
            Map<String, Object> response = mcpToolService.callTool(defaultServer, toolName, arguments, "chat:bridge-skill");
            Object resultObj = response.get("result");
            Map<String, Object> resultPayload;
            if (resultObj instanceof Map<?, ?> resultMap) {
                Object structuredContent = resultMap.get("structuredContent");
                if (structuredContent instanceof Map<?, ?> structuredMap) {
                    resultPayload = (Map<String, Object>) structuredMap;
                } else {
                    resultPayload = (Map<String, Object>) resultMap;
                }
            } else {
                resultPayload = Map.of("ok", true, "result", String.valueOf(resultObj));
            }
            toolEventService.recordToolResult(toolContext, toolName, resultPayload);
            return resultPayload;
        } catch (RuntimeException ex) {
            Map<String, Object> failure = Map.of(
                    "ok", false,
                    "error", "mcp tool call failed: " + ex.getMessage(),
                    "server", defaultServer,
                    "tool", toolName);
            toolEventService.recordToolResult(toolContext, toolName, failure);
            return failure;
        }
    }
}
