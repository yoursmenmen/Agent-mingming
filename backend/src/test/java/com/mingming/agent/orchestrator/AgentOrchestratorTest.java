package com.mingming.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.repository.AgentRunRepository;
import com.mingming.agent.repository.ChatSessionRepository;
import com.mingming.agent.repository.RunEventRepository;
import com.mingming.agent.tool.LocalToolProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.Message;
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
                runEventRepository,
                List.<LocalToolProvider>of());

        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(runEventRepository.findRecentConversationEvents(sessionId, 40)).thenReturn(List.of());
        when(runEventRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of());

        List<String> ssePayloads = new ArrayList<>();

        orchestrator.runOnce(runId, sessionId, "你好，测试消息", ssePayloads::add);

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
    void buildPromptMessages_shouldIncludeHistoryAndCurrentUserMessage() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelProvider,
                new ObjectMapper(),
                chatSessionRepository,
                agentRunRepository,
                runEventRepository,
                List.<LocalToolProvider>of());

        UUID previousRunId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        RunEventEntity historyUser = new RunEventEntity();
        historyUser.setRunId(previousRunId);
        historyUser.setType("USER_MESSAGE");
        historyUser.setPayload("{\"content\":\"历史问题\"}");

        RunEventEntity historyModel = new RunEventEntity();
        historyModel.setRunId(previousRunId);
        historyModel.setType("MODEL_MESSAGE");
        historyModel.setPayload("{\"content\":\"历史回答\"}");

        when(runEventRepository.findRecentConversationEvents(sessionId, 40))
                .thenReturn(List.of(historyModel, historyUser));

        List<Message> promptMessages = orchestrator.buildPromptMessages(sessionId, "当前问题");

        assertThat(promptMessages).hasSize(3);
        assertThat(promptMessages.get(0).getText()).isEqualTo("历史问题");
        assertThat(promptMessages.get(1).getText()).isEqualTo("历史回答");
        assertThat(promptMessages.get(2).getText()).isEqualTo("当前问题");
    }

    @Test
    void buildPromptMessages_shouldLimitHistorySizeForContextWindow() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelProvider,
                new ObjectMapper(),
                chatSessionRepository,
                agentRunRepository,
                runEventRepository,
                List.<LocalToolProvider>of());

        UUID sessionId = UUID.randomUUID();

        List<RunEventEntity> historyEvents = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            RunEventEntity event = new RunEventEntity();
            event.setRunId(UUID.randomUUID());
            event.setType("USER_MESSAGE");
            event.setPayload("{\"content\":\"历史消息" + i + "\"}");
            historyEvents.add(event);
        }
        when(runEventRepository.findRecentConversationEvents(sessionId, 40)).thenReturn(historyEvents);

        List<Message> promptMessages = orchestrator.buildPromptMessages(sessionId, "当前问题");

        assertThat(promptMessages).hasSize(20);
        assertThat(promptMessages.get(19).getText()).isEqualTo("当前问题");
    }

    @Test
    void startRun_shouldReuseExistingSessionId() {
        when(chatSessionRepository.existsById(any(UUID.class))).thenReturn(true);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelProvider,
                new ObjectMapper(),
                chatSessionRepository,
                agentRunRepository,
                runEventRepository,
                List.<LocalToolProvider>of());

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
                runEventRepository,
                List.<LocalToolProvider>of());

        UUID missingSessionId = UUID.randomUUID();

        assertThatThrownBy(() -> orchestrator.startRun(missingSessionId, "dashscope", null, null, "system.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId not found");
        verify(chatSessionRepository, never()).save(any());
        verify(agentRunRepository, never()).save(any());
    }

    @Test
    void buildFinalModelMessagePayload_shouldContainStructuredWeatherData() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                chatModelProvider,
                new ObjectMapper(),
                chatSessionRepository,
                agentRunRepository,
                runEventRepository,
                List.<LocalToolProvider>of());

        UUID runId = UUID.randomUUID();
        RunEventEntity weatherToolResult = new RunEventEntity();
        weatherToolResult.setType("TOOL_RESULT");
        weatherToolResult.setPayload("{\"tool\":\"get_weather\",\"data\":{\"ok\":true,\"city\":\"北京\",\"weather\":\"晴\",\"temperature\":\"26\",\"humidity\":\"42\",\"windDirection\":\"东南\",\"windPower\":\"3\",\"reportTime\":\"2026-03-29 17:00:00\"}}");
        when(runEventRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of(weatherToolResult));

        ObjectNode payload = orchestrator.buildFinalModelMessagePayload(runId, "北京现在晴，26度");

        assertThat(payload.path("content").asText()).isEqualTo("北京现在晴，26度");
        assertThat(payload.path("structured").path("schema").asText()).isEqualTo("weather.v1");
        assertThat(payload.path("structured").path("city").asText()).isEqualTo("北京");
        assertThat(payload.path("structured").path("weather").asText()).isEqualTo("晴");
    }
}
