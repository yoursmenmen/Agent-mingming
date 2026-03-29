package com.mingming.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.repository.AgentRunRepository;
import com.mingming.agent.repository.ChatSessionRepository;
import com.mingming.agent.repository.RunEventRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock
    private ObjectProvider<ChatModel> chatModelProvider;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private RunEventRepository runEventRepository;

    @Test
    void runOnce_shouldPersistUserAndModelEventsWithIncreasingSeq() throws Exception {
        when(chatModelProvider.getIfAvailable()).thenReturn(null);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelProvider,
                new ObjectMapper(),
                chatSessionRepository,
                agentRunRepository,
                runEventRepository);

        UUID runId = UUID.randomUUID();
        List<String> ssePayloads = new ArrayList<>();

        orchestrator.runOnce(runId, "你好，测试消息", ssePayloads::add);

        ArgumentCaptor<RunEventEntity> captor = ArgumentCaptor.forClass(RunEventEntity.class);
        verify(runEventRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        List<RunEventEntity> savedEvents = captor.getAllValues();
        assertThat(savedEvents).hasSize(2);

        RunEventEntity first = savedEvents.get(0);
        assertThat(first.getRunId()).isEqualTo(runId);
        assertThat(first.getSeq()).isEqualTo(1);
        assertThat(first.getType()).isEqualTo("USER_MESSAGE");
        assertThat(new ObjectMapper().readTree(first.getPayload()).path("content").asText())
                .isEqualTo("你好，测试消息");

        RunEventEntity second = savedEvents.get(1);
        assertThat(second.getRunId()).isEqualTo(runId);
        assertThat(second.getSeq()).isEqualTo(2);
        assertThat(second.getType()).isEqualTo("MODEL_MESSAGE");

        assertThat(ssePayloads).hasSize(1);
        assertThat(new ObjectMapper().readTree(ssePayloads.get(0)).has("content")).isTrue();
    }

    @Test
    void startRun_shouldReuseExistingSessionId() {
        when(chatSessionRepository.existsById(any(UUID.class))).thenReturn(true);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelProvider,
                new ObjectMapper(),
                chatSessionRepository,
                agentRunRepository,
                runEventRepository);

        UUID existingSessionId = UUID.randomUUID();
        AgentOrchestrator.RunInit runInit = orchestrator.startRun(existingSessionId, "dashscope", null, null, "system.txt");

        assertThat(runInit.sessionId()).isEqualTo(existingSessionId);
        verify(chatSessionRepository, never()).save(any());
        verify(agentRunRepository).save(any());
    }

    @Test
    void startRun_shouldThrowWhenSessionIdNotFound() {
        when(chatSessionRepository.existsById(any(UUID.class))).thenReturn(false);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelProvider,
                new ObjectMapper(),
                chatSessionRepository,
                agentRunRepository,
                runEventRepository);

        UUID missingSessionId = UUID.randomUUID();

        assertThatThrownBy(() -> orchestrator.startRun(missingSessionId, "dashscope", null, null, "system.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId not found");
        verify(chatSessionRepository, never()).save(any());
        verify(agentRunRepository, never()).save(any());
    }
}
