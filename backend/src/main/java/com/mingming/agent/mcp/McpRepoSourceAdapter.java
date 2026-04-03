package com.mingming.agent.mcp;

public interface McpRepoSourceAdapter {

    boolean supports(String repoUrl);

    McpRepoDescriptor resolve(String repoUrl);
}
