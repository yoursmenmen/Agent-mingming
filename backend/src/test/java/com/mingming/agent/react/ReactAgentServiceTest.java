package com.mingming.agent.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.orchestrator.AgentOrchestrator;
import com.mingming.agent.react.memory.SessionSummaryService;
import com.mingming.agent.react.tool.ToolDispatcher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class ReactAgentServiceTest {

    @Mock
    private ObjectProvider<ChatModel> chatModelProvider;

    @Mock
    private AgentOrchestrator orchestrator;

    @Mock
    private ToolDispatcher toolDispatcher;

    @Mock
    private SessionSummaryService summaryService;

    @Test
    void buildInitialMessages_shouldComposeSystemSummaryHistoryAndCurrentUser() {
        UUID sessionId = UUID.randomUUID();
        when(summaryService.loadLatestSummary(sessionId)).thenReturn(Optional.of("会话摘要"));
        when(orchestrator.buildSessionHistoryMessages(sessionId)).thenReturn(List.of(
                new UserMessage("历史用户"),
                new AssistantMessage("历史助手")));

        ReactAgentService service = new ReactAgentService(
                chatModelProvider,
                orchestrator,
                toolDispatcher,
                new ObjectMapper(),
                summaryService);

        List<Message> messages = service.buildInitialMessages(sessionId, "当前问题");

        assertThat(messages).hasSize(5);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1).getText()).contains("会话摘要");
        assertThat(messages.get(2).getText()).isEqualTo("历史用户");
        assertThat(messages.get(3).getText()).isEqualTo("历史助手");
        assertThat(messages.get(4)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(4).getText()).isEqualTo("当前问题");
    }
}
