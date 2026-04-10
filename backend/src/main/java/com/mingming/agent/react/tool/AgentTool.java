package com.mingming.agent.react.tool;

import java.util.Map;

public interface AgentTool {

    /** 工具名称，对应模型调用时的 function name */
    String name();

    /** 工具描述，用于生成 function schema */
    String description();

    /**
     * 工具参数 JSON Schema（OpenAI function calling 格式）
     */
    String inputSchema();

    /** 执行工具，args 为模型传入的参数 */
    ToolResult execute(Map<String, Object> args);
}
