package com.mingming.agent.tool;

public interface LocalToolProvider {

    ToolMetadata metadata();

    default Object toolBean() {
        return this;
    }
}
