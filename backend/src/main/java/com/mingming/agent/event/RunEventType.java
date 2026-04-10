package com.mingming.agent.event;

public enum RunEventType {
    USER_MESSAGE,
    MODEL_DELTA,
    MODEL_MESSAGE,
    RETRIEVAL_RESULT,
    RAG_SYNC,
    MCP_TOOLS_BOUND,
    MCP_CONFIRM_RESULT,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR
}
