package com.mingming.agent.rag;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VectorRagBootstrapSync {

    private static final Logger log = LoggerFactory.getLogger(VectorRagBootstrapSync.class);

    private final VectorRagProperties vectorRagProperties;
    private final SyncStatusService syncStatusService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent ignored) {
        if (!vectorRagProperties.isEnabled()) {
            return;
        }

        boolean accepted = syncStatusService.triggerWarmup();
        if (!accepted) {
            log.info("Vector RAG bootstrap sync skipped: already running or rejected");
        }
    }
}
