package com.mingming.agent.mcp;

public record McpServerConfig(
        String name,
        String transport,
        String url,
        String streaming,
        boolean enabled,
        int timeoutMs
) {}
