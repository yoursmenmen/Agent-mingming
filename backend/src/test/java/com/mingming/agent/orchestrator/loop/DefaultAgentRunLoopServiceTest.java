package com.mingming.agent.orchestrator.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class DefaultAgentRunLoopServiceTest {

    @Test
    void execute_shouldTerminateAtMaxRoundsAndRecordTerminatedEvent() {
        LongSupplier nowMs = increasingClock(1_000L, 10L);
        AgentRunLoopService loopService = new DefaultAgentRunLoopService(nowMs);
        LoopTerminationPolicy policy = new LoopTerminationPolicy(2, null, null);
        List<CapturedEvent> events = new ArrayList<>();

        LoopExecutionReport report = loopService.execute(
                policy,
                turnIndex -> new LoopStepResult(false, false),
                (type, turnIndex, elapsedMs, payload) -> events.add(new CapturedEvent(type, turnIndex, elapsedMs, payload)));

        assertThat(report.terminationReason()).contains(LoopTerminationReason.MAX_ROUNDS);
        assertThat(report.state().rounds()).isEqualTo(2);
        assertThat(events)
                .extracting(CapturedEvent::type)
                .containsExactly(
                        DefaultAgentRunLoopService.EVENT_LOOP_TURN_STARTED,
                        DefaultAgentRunLoopService.EVENT_LOOP_TURN_FINISHED,
                        DefaultAgentRunLoopService.EVENT_LOOP_TURN_STARTED,
                        DefaultAgentRunLoopService.EVENT_LOOP_TURN_FINISHED,
                        DefaultAgentRunLoopService.EVENT_LOOP_TERMINATED);

        CapturedEvent terminated = findFirstEventByType(events, DefaultAgentRunLoopService.EVENT_LOOP_TERMINATED);
        assertThat(terminated.turnIndex()).isEqualTo(2);
        assertThat(terminated.payload()).containsEntry("reason", "MAX_ROUNDS");
    }

    @Test
    void execute_shouldPreferFinalAnswerTerminationOverRoundLimit() {
        AgentRunLoopService loopService = new DefaultAgentRunLoopService(() -> 2_000L);
        LoopTerminationPolicy policy = new LoopTerminationPolicy(1, null, null);
        List<CapturedEvent> events = new ArrayList<>();

        LoopExecutionReport report = loopService.execute(
                policy,
                turnIndex -> new LoopStepResult(true, false),
                (type, turnIndex, elapsedMs, payload) -> events.add(new CapturedEvent(type, turnIndex, elapsedMs, payload)));

        assertThat(report.terminationReason()).contains(LoopTerminationReason.FINAL_ANSWER);
        CapturedEvent terminated = findFirstEventByType(events, DefaultAgentRunLoopService.EVENT_LOOP_TERMINATED);
        assertThat(terminated.payload()).containsEntry("reason", "FINAL_ANSWER");
    }

    @Test
    void execute_shouldTerminateOnConsecutiveToolFailures() {
        AgentRunLoopService loopService = new DefaultAgentRunLoopService(() -> 3_000L);
        LoopTerminationPolicy policy = new LoopTerminationPolicy(10, null, 2);

        LoopExecutionReport report = loopService.execute(
                policy,
                turnIndex -> new LoopStepResult(false, true),
                (type, turnIndex, elapsedMs, payload) -> {});

        assertThat(report.terminationReason()).contains(LoopTerminationReason.CONSECUTIVE_TOOL_FAILURES);
        assertThat(report.state().consecutiveToolFailures()).isEqualTo(2);
        assertThat(report.state().rounds()).isEqualTo(2);
    }

    @Test
    void execute_shouldRejectPolicyWithoutEffectiveTerminationLimits() {
        AgentRunLoopService loopService = new DefaultAgentRunLoopService(() -> 4_000L);
        LoopTerminationPolicy policy = new LoopTerminationPolicy(0, 0L, 0);

        assertThatThrownBy(
                        () -> loopService.execute(
                                policy,
                                turnIndex -> new LoopStepResult(false, false),
                                (type, turnIndex, elapsedMs, payload) -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one effective limit");
    }

    private CapturedEvent findFirstEventByType(List<CapturedEvent> events, String type) {
        return events.stream()
                .filter(event -> type.equals(event.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Event not found: " + type));
    }

    private LongSupplier increasingClock(long seed, long step) {
        AtomicLong now = new AtomicLong(seed - step);
        return () -> now.addAndGet(step);
    }

    private record CapturedEvent(String type, int turnIndex, long elapsedMs, Map<String, Object> payload) {}
}
