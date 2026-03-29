package com.mingming.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mingming.agent.service.RunEventQueryService;
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
    private RunEventQueryService runEventQueryService;

    @Test
    void getRunEvents_shouldDelegateToService() {
        RunsController controller = new RunsController(runEventQueryService);
        UUID runId = UUID.randomUUID();
        List<Map<String, Object>> expected = List.of(Map.of("runId", runId, "seq", 1));
        when(runEventQueryService.getRunEvents(runId)).thenReturn(expected);

        List<Map<String, Object>> result = controller.getRunEvents(runId);

        assertThat(result).isEqualTo(expected);
        verify(runEventQueryService).getRunEvents(runId);
    }

    @Test
    void getSessionEvents_shouldDelegateToService() {
        RunsController controller = new RunsController(runEventQueryService);
        UUID sessionId = UUID.randomUUID();
        List<Map<String, Object>> expected = List.of(Map.of("sessionId", sessionId));
        when(runEventQueryService.getSessionEvents(sessionId)).thenReturn(expected);

        List<Map<String, Object>> result = controller.getSessionEvents(sessionId);

        assertThat(result).isEqualTo(expected);
        verify(runEventQueryService).getSessionEvents(sessionId);
    }
}
