package com.mingming.agent.orchestrator.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mingming.agent.orchestrator.loop.LoopStepResult;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultTurnExecutionServiceTest {

    @Test
    void executeTurn_shouldNotMarkFinalAnswerReadyWithDefaultFallback() {
        DefaultTurnExecutionService service = new DefaultTurnExecutionService();
        TurnContext context = new TurnContext(
                "run-1", "session-1", "hello", 1, new AtomicInteger(0), payload -> {});

        LoopStepResult result = service.executeTurn(context);

        assertThat(result.finalAnswerReady()).isFalse();
        assertThat(result.toolFailure()).isFalse();
        assertThat(result.assistantContent()).isBlank();
    }

    @Test
    void executeTurn_shouldMarkFinalAnswerReadyWhenAssistantContentExists() {
        DefaultTurnExecutionService service = new DefaultTurnExecutionService(context -> "assistant says hi");
        TurnContext context = new TurnContext(
                "run-1", "session-1", "hello", 1, new AtomicInteger(0), payload -> {});

        LoopStepResult result = service.executeTurn(context);

        assertThat(result.finalAnswerReady()).isTrue();
        assertThat(result.toolFailure()).isFalse();
        assertThat(result.assistantContent()).isEqualTo("assistant says hi");
    }

    @Test
    void executeTurn_shouldMarkFinalAnswerNotReadyWhenAssistantContentIsBlank() {
        DefaultTurnExecutionService service = new DefaultTurnExecutionService(context -> "   ");
        TurnContext context = new TurnContext(
                "run-1", "session-1", "hello", 1, new AtomicInteger(0), payload -> {});

        LoopStepResult result = service.executeTurn(context);

        assertThat(result.finalAnswerReady()).isFalse();
        assertThat(result.toolFailure()).isFalse();
        assertThat(result.assistantContent()).isEqualTo("   ");
    }

    @Test
    void executeTurn_shouldThrowWhenContextIsNull() {
        DefaultTurnExecutionService service = new DefaultTurnExecutionService();

        assertThrows(NullPointerException.class, () -> service.executeTurn(null));
    }
}
