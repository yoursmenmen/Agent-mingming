package com.mingming.agent.orchestrator.loop;

import java.util.Optional;

public record LoopTerminationPolicy(Integer maxRounds, Long maxDurationMs, Integer maxConsecutiveToolFailures) {

    public boolean hasAnyEffectiveLimit() {
        return isPositive(maxRounds) || isPositive(maxDurationMs) || isPositive(maxConsecutiveToolFailures);
    }

    public Optional<LoopTerminationReason> check(LoopState state, long nowMs) {
        if (state.finalAnswerReady()) {
            return Optional.of(LoopTerminationReason.FINAL_ANSWER);
        }
        if (maxRounds != null && maxRounds > 0 && state.rounds() >= maxRounds) {
            return Optional.of(LoopTerminationReason.MAX_ROUNDS);
        }
        if (maxDurationMs != null && maxDurationMs > 0 && nowMs - state.startedAtMs() >= maxDurationMs) {
            return Optional.of(LoopTerminationReason.TIMEOUT);
        }
        if (maxConsecutiveToolFailures != null
                && maxConsecutiveToolFailures > 0
                && state.consecutiveToolFailures() >= maxConsecutiveToolFailures) {
            return Optional.of(LoopTerminationReason.CONSECUTIVE_TOOL_FAILURES);
        }
        return Optional.empty();
    }

    private static boolean isPositive(Number value) {
        return value != null && value.longValue() > 0;
    }
}
