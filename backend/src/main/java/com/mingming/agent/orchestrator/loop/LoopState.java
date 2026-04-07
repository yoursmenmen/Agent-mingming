package com.mingming.agent.orchestrator.loop;

public record LoopState(long startedAtMs, int rounds, int consecutiveToolFailures, boolean finalAnswerReady) {}
