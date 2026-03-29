package com.mingming.agent.tool;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ToolRunContextHolder {

    private static final ThreadLocal<RunContext> CONTEXT = new ThreadLocal<>();

    public void start(UUID runId, AtomicInteger seq) {
        CONTEXT.set(new RunContext(runId, seq));
    }

    public void clear() {
        CONTEXT.remove();
    }

    public UUID currentRunId() {
        RunContext context = CONTEXT.get();
        return context == null ? null : context.runId();
    }

    public Integer nextSeq() {
        RunContext context = CONTEXT.get();
        return context == null ? null : context.seq().getAndIncrement();
    }

    private record RunContext(UUID runId, AtomicInteger seq) {}
}
