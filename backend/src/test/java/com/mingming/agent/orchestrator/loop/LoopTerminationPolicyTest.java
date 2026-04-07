package com.mingming.agent.orchestrator.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoopTerminationPolicyTest {

    @Test
    void returnsMaxRoundsWhenRoundLimitReached() {
        LoopTerminationPolicy policy = new LoopTerminationPolicy(3, 30_000L, 2);
        LoopState state = new LoopState(1_000L, 3, 0, false);

        assertThat(policy.check(state, 1_500L)).contains(LoopTerminationReason.MAX_ROUNDS);
    }
}
