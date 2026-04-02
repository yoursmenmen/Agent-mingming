package com.mingming.agent.controller;

import com.mingming.agent.mcp.McpToolService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpToolService mcpToolService;

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

    public record McpToolCallRequest(String server, String toolName, Map<String, Object> arguments) {}

    public record McpServerEnabledRequest(String server, boolean enabled) {}
}
