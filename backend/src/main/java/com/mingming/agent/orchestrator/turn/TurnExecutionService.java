package com.mingming.agent.orchestrator.turn;

import com.mingming.agent.orchestrator.loop.LoopStepResult;

public interface TurnExecutionService {

    LoopStepResult executeTurn(TurnContext context);
}
