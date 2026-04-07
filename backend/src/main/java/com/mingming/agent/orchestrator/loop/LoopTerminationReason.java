package com.mingming.agent.orchestrator.loop;

public enum LoopTerminationReason {
    FINAL_ANSWER,
    MAX_ROUNDS,
    TIMEOUT,
    CONSECUTIVE_TOOL_FAILURES
}
