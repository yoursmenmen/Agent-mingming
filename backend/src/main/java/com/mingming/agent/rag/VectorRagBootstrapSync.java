package com.mingming.agent.rag;

import java.nio.file.Path;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VectorRagBootstrapSync {

    private static final Logger log = LoggerFactory.getLogger(VectorRagBootstrapSync.class);

    private final VectorChunkSyncService vectorChunkSyncService;
    private final VectorRagProperties vectorRagProperties;
    private final TaskExecutor taskExecutor;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent ignored) {
        if (!vectorRagProperties.isEnabled()) {
            return;
        }

        try {
            taskExecutor.execute(() -> {
                try {
                    Path docsRoot = Path.of(vectorRagProperties.getDocsRoot());
                    VectorChunkSyncService.SyncSummary summary = vectorChunkSyncService.sync(docsRoot);
                    log.info(
                            "Vector RAG bootstrap sync finished: docsRoot={}, inserted={}, updated={}, softDeleted={}, unchanged={}",
                            docsRoot,
                            summary.inserted(),
                            summary.updated(),
                            summary.softDeleted(),
                            summary.unchanged());
                } catch (RuntimeException ex) {
                    log.warn(
                            "Vector RAG bootstrap sync failed: docsRootRaw={}, exceptionType={}, message={}",
                            vectorRagProperties.getDocsRoot(),
                            ex.getClass().getSimpleName(),
                            ex.getMessage(),
                            ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            log.warn(
                    "Vector RAG bootstrap sync skipped: task submission rejected, docsRootRaw={}, exceptionType={}, message={}",
                    vectorRagProperties.getDocsRoot(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex);
        } catch (RuntimeException ex) {
            log.warn(
                    "Vector RAG bootstrap sync skipped: task submission failed, docsRootRaw={}, exceptionType={}, message={}",
                    vectorRagProperties.getDocsRoot(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex);
        }
    }
}
