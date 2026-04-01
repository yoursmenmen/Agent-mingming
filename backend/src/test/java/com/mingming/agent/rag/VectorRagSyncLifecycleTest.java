package com.mingming.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mingming.agent.repository.DocChunkEmbeddingRepository;
import com.mingming.agent.repository.DocChunkRepository;
import com.mingming.agent.repository.RagSourceSyncStateRepository;
import com.mingming.agent.rag.source.UrlSourceProperties;
import java.util.List;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

@ExtendWith(MockitoExtension.class)
class VectorRagSyncLifecycleTest {

    @Mock
    private VectorChunkSyncService vectorChunkSyncService;

    @Mock
    private VectorRagProperties vectorRagProperties;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private DocChunkRepository docChunkRepository;

    @Mock
    private DocChunkEmbeddingRepository embeddingRepository;

    @Mock
    private RetrievalEventService retrievalEventService;

    @Mock
    private UrlSourceProperties urlSourceProperties;

    @Mock
    private RagSourceSyncStateRepository sourceSyncStateRepository;

    @Test
    void trigger_shouldTransitionFromRunningToCompleted() {
        when(vectorRagProperties.isEnabled()).thenReturn(true);
        when(vectorRagProperties.getDocsRoot()).thenReturn("docs");
        when(vectorChunkSyncService.sync(any())).thenReturn(new VectorChunkSyncService.SyncSummary(1, 0, 0, 0));
        when(docChunkRepository.findByDeletedFalse()).thenReturn(List.of());
        when(embeddingRepository.count()).thenReturn(0L);
        doAnswer(invocation -> {
                    Runnable task = invocation.getArgument(0);
                    task.run();
                    return null;
                })
                .when(taskExecutor)
                .execute(any(Runnable.class));

        SyncStatusService service = new SyncStatusService(
                vectorChunkSyncService,
                vectorRagProperties,
                taskExecutor,
                docChunkRepository,
                embeddingRepository,
                retrievalEventService,
                urlSourceProperties,
                sourceSyncStateRepository);

        boolean accepted = service.trigger();
        SyncStatusService.Snapshot status = service.status();

        assertThat(accepted).isTrue();
        assertThat(status.state()).isEqualTo("completed");
        assertThat(status.lastSuccessAt()).isBeforeOrEqualTo(OffsetDateTime.now());
        verify(retrievalEventService).recordRagSync(null, 0, "started", "manual", null, null);
        verify(retrievalEventService)
                .recordRagSync(null, 0, "completed", "manual", new VectorChunkSyncService.SyncSummary(1, 0, 0, 0), null);
    }

    @Test
    void trigger_shouldTransitionFromRunningToFailedWhenSyncThrows() {
        when(vectorRagProperties.isEnabled()).thenReturn(true);
        when(vectorRagProperties.getDocsRoot()).thenReturn("docs");
        doThrow(new IllegalStateException("sync boom")).when(vectorChunkSyncService).sync(any());
        when(docChunkRepository.findByDeletedFalse()).thenReturn(List.of());
        when(embeddingRepository.count()).thenReturn(0L);
        doAnswer(invocation -> {
                    Runnable task = invocation.getArgument(0);
                    task.run();
                    return null;
                })
                .when(taskExecutor)
                .execute(any(Runnable.class));

        SyncStatusService service = new SyncStatusService(
                vectorChunkSyncService,
                vectorRagProperties,
                taskExecutor,
                docChunkRepository,
                embeddingRepository,
                retrievalEventService,
                urlSourceProperties,
                sourceSyncStateRepository);

        boolean accepted = service.trigger();
        SyncStatusService.Snapshot status = service.status();

        assertThat(accepted).isTrue();
        assertThat(status.state()).isEqualTo("failed");
        assertThat(status.lastError()).contains("sync boom");
        verify(retrievalEventService).recordRagSync(null, 0, "started", "manual", null, null);
        verify(retrievalEventService)
                .recordRagSync(null, 0, "failed", "manual", null, "sync boom");
    }
}
