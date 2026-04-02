package com.mingming.agent.event;

public enum RunEventType {
    USER_MESSAGE,
    MODEL_DELTA,
    MODEL_MESSAGE,
    RETRIEVAL_RESULT,
    RAG_SYNC,
    MCP_TOOLS_BOUND,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR
}
