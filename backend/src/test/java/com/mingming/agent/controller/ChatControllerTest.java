package com.mingming.agent.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.orchestrator.AgentOrchestrator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private AgentOrchestrator orchestrator;

    @Test
    void chatStream_shouldDelegateLoopExecutionWithExpectedArguments() throws Exception {
        ChatController controller = new ChatController(orchestrator, new ObjectMapper());
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        CountDownLatch executeCalled = new CountDownLatch(1);
        when(orchestrator.startRun(null, "dashscope", null, null, "system.txt"))
                .thenReturn(new AgentOrchestrator.RunInit(sessionId, runId));
        doAnswer(invocation -> {
            executeCalled.countDown();
            return null;
        }).when(orchestrator).executeSingleTurn(eq(runId), eq(sessionId), eq("hello"), any());

        controller.chatStream(new ChatController.ChatRequest("hello", null));

        Assertions.assertTrue(executeCalled.await(2, TimeUnit.SECONDS));
        verify(orchestrator).executeSingleTurn(eq(runId), eq(sessionId), eq("hello"), any());
        verify(orchestrator).startRun(eq(null), eq("dashscope"), eq(null), eq(null), eq("system.txt"));
    }
}
