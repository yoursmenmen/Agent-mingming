package com.mingming.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mingming.agent.rag.SyncStatusService;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RagSyncControllerTest {

    @Mock
    private SyncStatusService syncStatusService;

    @Test
    void getStatus_shouldReturnStatusPayload() {
        RagSyncController controller = new RagSyncController(syncStatusService);
        OffsetDateTime lastStartAt = OffsetDateTime.parse("2026-04-01T10:00:00Z");
        OffsetDateTime lastSuccessAt = OffsetDateTime.parse("2026-04-01T10:01:00Z");
        SyncStatusService.Snapshot snapshot = new SyncStatusService.Snapshot(
                "idle", lastStartAt, lastSuccessAt, null, 12, 12, new SyncStatusService.SourceStats(3, 0));
        when(syncStatusService.status()).thenReturn(snapshot);

        Map<String, Object> result = controller.getStatus();

        assertThat(result)
                .containsEntry("state", "idle")
                .containsEntry("chunkCount", 12L)
                .containsEntry("embeddingCount", 12L)
                .containsEntry("lastStartAt", lastStartAt)
                .containsEntry("lastSuccessAt", lastSuccessAt);
        assertThat(result.get("sourceStats"))
                .isEqualTo(Map.of("localDocs", 3L, "urlSources", 0L));
        verify(syncStatusService).status();
    }

    @Test
    void trigger_shouldReturnAcceptedAndStatus() {
        RagSyncController controller = new RagSyncController(syncStatusService);
        SyncStatusService.Snapshot snapshot = new SyncStatusService.Snapshot(
                "running", OffsetDateTime.parse("2026-04-01T10:00:00Z"), null, null, 15, 10, new SyncStatusService.SourceStats(4, 0));
        when(syncStatusService.trigger()).thenReturn(true);
        when(syncStatusService.status()).thenReturn(snapshot);

        Map<String, Object> result = controller.trigger();

        assertThat(result).containsEntry("accepted", true);
        assertThat(result.get("status")).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) result.get("status");

        assertThat(status)
                .containsEntry("state", "running")
                .containsEntry("chunkCount", 15L)
                .containsEntry("embeddingCount", 10L);
        assertThat(status.get("sourceStats"))
                .isEqualTo(Map.of("localDocs", 4L, "urlSources", 0L));

        verify(syncStatusService).trigger();
        verify(syncStatusService).status();
    }
}
