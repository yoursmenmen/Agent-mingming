package com.mingming.agent.orchestrator.turn;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public record TurnContext(
        String runId,
        String sessionId,
        String userText,
        int turnIndex,
        AtomicInteger seq,
        Consumer<String> sseDataConsumer) {}
