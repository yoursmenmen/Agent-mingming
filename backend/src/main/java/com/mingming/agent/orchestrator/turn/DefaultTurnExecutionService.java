package com.mingming.agent.orchestrator.turn;

import com.mingming.agent.orchestrator.loop.LoopStepResult;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class DefaultTurnExecutionService implements TurnExecutionService {

    private final Function<TurnContext, String> assistantGenerator;

    public DefaultTurnExecutionService() {
        this(context -> "");
    }

    DefaultTurnExecutionService(Function<TurnContext, String> assistantGenerator) {
        this.assistantGenerator = Objects.requireNonNull(assistantGenerator, "assistantGenerator must not be null");
    }

    @Override
    public LoopStepResult executeTurn(TurnContext context) {
        Objects.requireNonNull(context, "context must not be null");
        String assistantContent = assistantGenerator.apply(context);
        boolean finalAnswerReady = assistantContent != null && !assistantContent.isBlank();
        return new LoopStepResult(finalAnswerReady, false, 0, assistantContent, Map.of());
    }
}
