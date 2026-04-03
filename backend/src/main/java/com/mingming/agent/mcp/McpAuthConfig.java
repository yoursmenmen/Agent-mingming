package com.mingming.agent.mcp;

public record McpAuthConfig(
        String type,
        String tokenEnv,
        String token,
        String headerName) {

    public McpAuthConfig(String type, String tokenEnv, String headerName) {
        this(type, tokenEnv, null, headerName);
    }
}
