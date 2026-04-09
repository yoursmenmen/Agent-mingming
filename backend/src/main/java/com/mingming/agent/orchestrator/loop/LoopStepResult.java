package com.mingming.agent.orchestrator.loop;

import java.util.Map;

public record LoopStepResult(
        boolean finalAnswerReady,
        boolean toolFailure,
        int toolCallCount,
        String assistantContent,
        Map<String, Object> meta) {

    public LoopStepResult {
        if (toolCallCount < 0) {
            throw new IllegalArgumentException("toolCallCount must be >= 0");
        }
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public LoopStepResult(boolean finalAnswerReady, boolean toolFailure) {
        this(finalAnswerReady, toolFailure, 0, null, Map.of());
    }
}
