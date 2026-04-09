package com.mingming.agent.orchestrator.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.mingming.agent.orchestrator.loop.LoopStepResult;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TurnExecutionServiceContractTest {

    @Test
    void executeTurn_shouldReturnLoopStepResultWithAssertableCoreFields() {
        TurnExecutionService service = context -> new LoopStepResult(true, false);
        AtomicInteger seq = new AtomicInteger(3);
        TurnContext context = new TurnContext("run-1", "session-1", "hello", 1, seq, payload -> {});

        LoopStepResult result = service.executeTurn(context);

        assertThat(result.finalAnswerReady()).isTrue();
        assertThat(result.toolFailure()).isFalse();
    }
}
