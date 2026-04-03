package com.mingming.agent.mcp;

public record McpRepoDescriptor(
        String source,
        String owner,
        String repo,
        String cloneUrl,
        String readmeText) {}
