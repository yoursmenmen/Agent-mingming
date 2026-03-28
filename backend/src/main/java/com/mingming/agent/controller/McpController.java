package com.mingming.agent.controller;

import com.mingming.agent.mcp.McpServerRegistry;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class McpController {

    private final McpServerRegistry registry;

    @GetMapping("/api/mcp/servers")
    public Object listServers() {
        return registry.load();
    }

    @GetMapping("/api/mcp/tools")
    public Object listTools() {
        // MVP skeleton: discovery + adaptation to Spring AI tools will be implemented next.
        return Map.of("tools", java.util.List.of());
    }
}
