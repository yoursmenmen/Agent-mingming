package com.mingming.agent.react;

public record TerminationPolicy(
        int maxTurns,
        long maxDurationMs,
        int maxConsecutiveErrors) {

    public static TerminationPolicy defaults() {
        return new TerminationPolicy(10, 120_000L, 3);
    }
}
