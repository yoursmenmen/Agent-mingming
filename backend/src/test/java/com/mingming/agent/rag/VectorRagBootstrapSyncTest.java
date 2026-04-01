package com.mingming.agent.rag;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@ExtendWith(MockitoExtension.class)
class VectorRagBootstrapSyncTest {

    @Mock
    private VectorRagProperties vectorRagProperties;

    @Mock
    private SyncStatusService syncStatusService;

    @Mock
    private ApplicationReadyEvent applicationReadyEvent;

    @Test
    void onApplicationReady_shouldScheduleBackgroundSyncWhenEnabled() {
        when(vectorRagProperties.isEnabled()).thenReturn(true);
        when(syncStatusService.triggerWarmup()).thenReturn(true);

        VectorRagBootstrapSync bootstrapSync =
                new VectorRagBootstrapSync(vectorRagProperties, syncStatusService);

        bootstrapSync.onApplicationReady(applicationReadyEvent);

        verify(syncStatusService).triggerWarmup();
    }

    @Test
    void onApplicationReady_shouldSkipSyncWhenDisabled() {
        when(vectorRagProperties.isEnabled()).thenReturn(false);

        VectorRagBootstrapSync bootstrapSync =
                new VectorRagBootstrapSync(vectorRagProperties, syncStatusService);

        bootstrapSync.onApplicationReady(applicationReadyEvent);

        verify(syncStatusService, never()).triggerWarmup();
    }
}
