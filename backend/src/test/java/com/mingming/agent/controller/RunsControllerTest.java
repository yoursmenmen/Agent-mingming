package com.mingming.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mingming.agent.entity.AgentRunEntity;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.repository.AgentRunRepository;
import com.mingming.agent.repository.RunEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunsControllerTest {

    @Mock
    private RunEventRepository runEventRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Test
    void getSessionEvents_shouldAggregateEventsAcrossRunsInOrder() {
        RunsController controller = new RunsController(runEventRepository, agentRunRepository);
        UUID sessionId = UUID.randomUUID();

        UUID runId1 = UUID.randomUUID();
        UUID runId2 = UUID.randomUUID();

        AgentRunEntity run1 = new AgentRunEntity();
        run1.setId(runId1);
        AgentRunEntity run2 = new AgentRunEntity();
        run2.setId(runId2);
        when(agentRunRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(run1, run2));

        RunEventEntity event1 = new RunEventEntity();
        event1.setId(UUID.randomUUID());
        event1.setRunId(runId1);
        event1.setSeq(1);
        event1.setType("USER_MESSAGE");
        event1.setPayload("{\"content\":\"Q1\"}");
        event1.setCreatedAt(OffsetDateTime.parse("2026-03-29T10:00:00+08:00"));

        RunEventEntity event2 = new RunEventEntity();
        event2.setId(UUID.randomUUID());
        event2.setRunId(runId2);
        event2.setSeq(1);
        event2.setType("USER_MESSAGE");
        event2.setPayload("{\"content\":\"Q2\"}");
        event2.setCreatedAt(OffsetDateTime.parse("2026-03-29T10:01:00+08:00"));

        when(runEventRepository.findByRunIdInOrderByCreatedAtAscSeqAsc(anyList())).thenReturn(List.of(event1, event2));

        List<Map<String, Object>> result = controller.getSessionEvents(sessionId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("runId")).isEqualTo(runId1);
        assertThat(result.get(1).get("runId")).isEqualTo(runId2);
        verify(runEventRepository).findByRunIdInOrderByCreatedAtAscSeqAsc(List.of(runId1, runId2));
    }
}
