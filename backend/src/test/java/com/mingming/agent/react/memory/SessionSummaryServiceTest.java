package com.mingming.agent.react.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.orchestrator.AgentOrchestrator;
import com.mingming.agent.repository.RunEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionSummaryServiceTest {

    @Mock
    private RunEventRepository runEventRepository;

    @Mock
    private AgentOrchestrator orchestrator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadLatestSummary_shouldReturnContentFromLatestEvent() {
        UUID sessionId = UUID.randomUUID();
        RunEventEntity entity = new RunEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setRunId(UUID.randomUUID());
        entity.setSeq(9);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setType(RunEventType.SESSION_SUMMARY.name());
        entity.setPayload("{\"content\":\"旧摘要\"}");
        when(runEventRepository.findLatestSessionSummaryEvent(sessionId)).thenReturn(Optional.of(entity));

        SessionSummaryService service = new SessionSummaryService(runEventRepository, orchestrator, objectMapper);

        assertThat(service.loadLatestSummary(sessionId)).contains("旧摘要");
    }

    @Test
    void refreshSummary_shouldPersistSummaryEventAndIncreaseSeq() {
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AtomicInteger seq = new AtomicInteger(5);

        SessionSummaryService service = new SessionSummaryService(runEventRepository, orchestrator, objectMapper);
        Optional<String> summary = service.refreshSummary(
                runId,
                sessionId,
                "旧摘要",
                List.of(new SessionSummaryService.ConversationTurn("用户问题", "助手回答")),
                null,
                seq);

        assertThat(summary).isPresent();
        assertThat(seq.get()).isEqualTo(6);

        ArgumentCaptor<ObjectNode> payloadCaptor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(orchestrator).appendEvent(eq(runId), eq(5), eq(RunEventType.SESSION_SUMMARY), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().path("sessionId").asText()).isEqualTo(sessionId.toString());
        assertThat(payloadCaptor.getValue().path("content").asText()).isNotBlank();
    }
}
