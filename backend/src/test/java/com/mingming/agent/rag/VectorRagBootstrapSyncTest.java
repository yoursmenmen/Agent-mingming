package com.mingming.agent.rag;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.task.TaskExecutor;

@ExtendWith(MockitoExtension.class)
class VectorRagBootstrapSyncTest {

    @Mock
    private VectorChunkSyncService vectorChunkSyncService;

    @Mock
    private VectorRagProperties vectorRagProperties;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private ApplicationReadyEvent applicationReadyEvent;

    @Test
    void onApplicationReady_shouldScheduleBackgroundSyncWhenEnabled() {
        when(vectorRagProperties.isEnabled()).thenReturn(true);
        when(vectorRagProperties.getDocsRoot()).thenReturn("../docs");
        when(vectorChunkSyncService.sync(Path.of("../docs")))
                .thenReturn(new VectorChunkSyncService.SyncSummary(1, 2, 3, 4));

        VectorRagBootstrapSync bootstrapSync =
                new VectorRagBootstrapSync(vectorChunkSyncService, vectorRagProperties, taskExecutor);

        bootstrapSync.onApplicationReady(applicationReadyEvent);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(runnableCaptor.capture());
        verify(vectorChunkSyncService, never()).sync(Path.of("../docs"));

        runnableCaptor.getValue().run();
        verify(vectorChunkSyncService).sync(Path.of("../docs"));
    }

    @Test
    void onApplicationReady_shouldSkipSyncWhenDisabled() {
        when(vectorRagProperties.isEnabled()).thenReturn(false);

        VectorRagBootstrapSync bootstrapSync =
                new VectorRagBootstrapSync(vectorChunkSyncService, vectorRagProperties, taskExecutor);

        bootstrapSync.onApplicationReady(applicationReadyEvent);

        verify(taskExecutor, never()).execute(org.mockito.ArgumentMatchers.any());
        verify(vectorChunkSyncService, never()).sync(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onApplicationReady_shouldNotPropagateExceptionFromBackgroundSync() {
        when(vectorRagProperties.isEnabled()).thenReturn(true);
        when(vectorRagProperties.getDocsRoot()).thenReturn("../docs");
        doThrow(new RuntimeException("sync failed"))
                .when(vectorChunkSyncService)
                .sync(Path.of("../docs"));

        VectorRagBootstrapSync bootstrapSync =
                new VectorRagBootstrapSync(vectorChunkSyncService, vectorRagProperties, taskExecutor);

        bootstrapSync.onApplicationReady(applicationReadyEvent);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(runnableCaptor.capture());
        Assertions.assertDoesNotThrow(() -> runnableCaptor.getValue().run());
        verify(vectorChunkSyncService).sync(Path.of("../docs"));
    }

    @Test
    void onApplicationReady_shouldNotPropagateWhenTaskSubmissionRejected() {
        when(vectorRagProperties.isEnabled()).thenReturn(true);
        when(vectorRagProperties.getDocsRoot()).thenReturn("../docs");
        doThrow(new RejectedExecutionException("executor full"))
                .when(taskExecutor)
                .execute(any(Runnable.class));

        VectorRagBootstrapSync bootstrapSync =
                new VectorRagBootstrapSync(vectorChunkSyncService, vectorRagProperties, taskExecutor);

        Assertions.assertDoesNotThrow(() -> bootstrapSync.onApplicationReady(applicationReadyEvent));
        verify(vectorChunkSyncService, never()).sync(any(Path.class));
    }

    @Test
    void onApplicationReady_shouldNotPropagateWhenDocsRootInvalid() {
        when(vectorRagProperties.isEnabled()).thenReturn(true);
        when(vectorRagProperties.getDocsRoot()).thenReturn("bad\u0000path");
        doAnswer(invocation -> {
                    Runnable runnable = invocation.getArgument(0);
                    runnable.run();
                    return null;
                })
                .when(taskExecutor)
                .execute(any(Runnable.class));

        VectorRagBootstrapSync bootstrapSync =
                new VectorRagBootstrapSync(vectorChunkSyncService, vectorRagProperties, taskExecutor);

        Assertions.assertDoesNotThrow(() -> bootstrapSync.onApplicationReady(applicationReadyEvent));
        verify(vectorChunkSyncService, never()).sync(any(Path.class));
    }
}
