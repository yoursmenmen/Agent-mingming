package com.mingming.agent.orchestrator.loop;

import java.util.Optional;

public record LoopExecutionReport(LoopState state, Optional<LoopTerminationReason> terminationReason) {

    public LoopExecutionReport {
        terminationReason = terminationReason == null ? Optional.empty() : terminationReason;
    }
}
