package com.mingming.agent.mcp;

import java.util.List;
import java.util.Map;

public record McpServerConfig(
        String name,
        String transport,
        String url,
        String command,
        String workingDir,
        List<String> args,
        Map<String, String> env,
        String streaming,
        boolean enabled,
        int timeoutMs,
        McpAuthConfig auth
) {

    public McpServerConfig(
            String name,
            String transport,
            String url,
            String streaming,
            boolean enabled,
            int timeoutMs) {
        this(name, transport, url, null, null, List.of(), Map.of(), streaming, enabled, timeoutMs, null);
    }
}
