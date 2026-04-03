package com.mingming.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.contract.EventContractRegistry;
import com.mingming.agent.event.contract.McpConfirmResultEventContract;
import com.mingming.agent.event.contract.RetrievalResultEventContract;
import com.mingming.agent.event.contract.ToolResultEventContract;
import com.mingming.agent.repository.RunEventRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class ToolEventServiceTest {

    @Mock
    private RunEventRepository runEventRepository;

    @Test
    void recordToolCall_shouldPersistToolCallEvent() throws Exception {
        UUID runId = UUID.randomUUID();
        ToolContext toolContext = new ToolContext(Map.of(
                "runId", runId.toString(),
                "seqCounter", new AtomicInteger(3)));

        ToolEventService service = new ToolEventService(runEventRepository, new ObjectMapper(), newRegistry());
        service.recordToolCall(toolContext, "add", Map.of("a", 1, "b", 2));

        ArgumentCaptor<RunEventEntity> captor = ArgumentCaptor.forClass(RunEventEntity.class);
        verify(runEventRepository).save(captor.capture());
        RunEventEntity saved = captor.getValue();

        assertThat(saved.getRunId()).isEqualTo(runId);
        assertThat(saved.getSeq()).isEqualTo(3);
        assertThat(saved.getType()).isEqualTo("TOOL_CALL");

        ObjectNode payload = (ObjectNode) new ObjectMapper().readTree(saved.getPayload());
        assertThat(payload.path("tool").asText()).isEqualTo("add");
        assertThat(payload.path("data").path("a").asInt()).isEqualTo(1);
        assertThat(payload.path("data").path("b").asInt()).isEqualTo(2);
    }

    private EventContractRegistry newRegistry() {
        return new EventContractRegistry(List.of(
                new ToolResultEventContract(),
                new McpConfirmResultEventContract(),
                new RetrievalResultEventContract()));
    }
}
