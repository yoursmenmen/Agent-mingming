package com.mingming.agent.orchestrator.loop;

import java.util.Map;

public interface AgentRunLoopService {

    LoopExecutionReport execute(
            LoopTerminationPolicy policy, LoopTurnExecutor turnExecutor, LoopEventListener eventListener);

    @FunctionalInterface
    interface LoopTurnExecutor {
        LoopStepResult execute(int turnIndex);
    }

    @FunctionalInterface
    interface LoopEventListener {
        void onEvent(String eventType, int turnIndex, long elapsedMs, Map<String, Object> payload);
    }
}
