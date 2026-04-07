package com.mingming.agent.orchestrator.loop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;

@Service
public class DefaultAgentRunLoopService implements AgentRunLoopService {

    public static final String EVENT_LOOP_TURN_STARTED = "LOOP_TURN_STARTED";
    public static final String EVENT_LOOP_TURN_FINISHED = "LOOP_TURN_FINISHED";
    public static final String EVENT_LOOP_TERMINATED = "LOOP_TERMINATED";

    private final LongSupplier nowMsSupplier;

    public DefaultAgentRunLoopService() {
        this(System::currentTimeMillis);
    }

    DefaultAgentRunLoopService(LongSupplier nowMsSupplier) {
        this.nowMsSupplier = Objects.requireNonNull(nowMsSupplier, "nowMsSupplier must not be null");
    }

    @Override
    public LoopExecutionReport execute(
            LoopTerminationPolicy policy, LoopTurnExecutor turnExecutor, LoopEventListener eventListener) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(turnExecutor, "turnExecutor must not be null");
        Objects.requireNonNull(eventListener, "eventListener must not be null");
        if (!policy.hasAnyEffectiveLimit()) {
            throw new IllegalArgumentException(
                    "LoopTerminationPolicy must define at least one effective limit: maxRounds > 0, maxDurationMs > 0, or maxConsecutiveToolFailures > 0");
        }

        long startedAtMs = nowMsSupplier.getAsLong();
        LoopState state = new LoopState(startedAtMs, 0, 0, false);

        while (true) {
            long nowMs = nowMsSupplier.getAsLong();
            java.util.Optional<LoopTerminationReason> terminationReason = policy.check(state, nowMs);
            if (terminationReason.isPresent()) {
                eventListener.onEvent(
                        EVENT_LOOP_TERMINATED,
                        state.rounds(),
                        Math.max(0L, nowMs - startedAtMs),
                        terminationPayload(terminationReason.get(), state));
                return new LoopExecutionReport(state, terminationReason);
            }

            int turnIndex = state.rounds() + 1;
            eventListener.onEvent(
                    EVENT_LOOP_TURN_STARTED,
                    turnIndex,
                    Math.max(0L, nowMs - startedAtMs),
                    Map.of());

            LoopStepResult stepResult = Objects.requireNonNull(turnExecutor.execute(turnIndex), "stepResult must not be null");
            int nextConsecutiveFailures = stepResult.toolFailure() ? state.consecutiveToolFailures() + 1 : 0;
            state = new LoopState(startedAtMs, turnIndex, nextConsecutiveFailures, stepResult.finalAnswerReady());

            long finishedAtMs = nowMsSupplier.getAsLong();
            eventListener.onEvent(
                    EVENT_LOOP_TURN_FINISHED,
                    turnIndex,
                    Math.max(0L, finishedAtMs - startedAtMs),
                    Map.of(
                            "finalAnswerReady", stepResult.finalAnswerReady(),
                            "toolFailure", stepResult.toolFailure(),
                            "consecutiveToolFailures", state.consecutiveToolFailures()));
        }
    }

    private Map<String, Object> terminationPayload(LoopTerminationReason reason, LoopState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason.name());
        payload.put("finalAnswerPresent", state.finalAnswerReady());
        return payload;
    }
}
