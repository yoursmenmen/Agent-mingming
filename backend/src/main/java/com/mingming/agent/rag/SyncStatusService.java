package com.mingming.agent.rag;

import com.mingming.agent.entity.DocChunkEntity;
import com.mingming.agent.entity.RagSourceSyncStateEntity;
import com.mingming.agent.rag.source.UrlSourceIdUtil;
import com.mingming.agent.rag.source.UrlSourceProperties;
import com.mingming.agent.repository.DocChunkEmbeddingRepository;
import com.mingming.agent.repository.DocChunkRepository;
import com.mingming.agent.repository.RagSourceSyncStateRepository;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SyncStatusService {

    private static final Logger log = LoggerFactory.getLogger(SyncStatusService.class);

    private final VectorChunkSyncService vectorChunkSyncService;
    private final VectorRagProperties vectorRagProperties;
    private final TaskExecutor taskExecutor;
    private final DocChunkRepository docChunkRepository;
    private final DocChunkEmbeddingRepository embeddingRepository;
    private final RetrievalEventService retrievalEventService;
    private final UrlSourceProperties urlSourceProperties;
    private final RagSourceSyncStateRepository sourceSyncStateRepository;

    private final Object lock = new Object();

    private SyncState state = SyncState.IDLE;
    private OffsetDateTime lastStartAt;
    private OffsetDateTime lastSuccessAt;
    private String lastError;

    public Snapshot status() {
        List<DocChunkEntity> activeChunks = docChunkRepository.findByDeletedFalse();
        Set<String> localDocs = activeChunks.stream()
                .filter(chunk -> "local_docs".equalsIgnoreCase(chunk.getSourceType()) || chunk.getSourceType() == null)
                .map(DocChunkEntity::getDocPath)
                .collect(Collectors.toSet());
        Set<String> urlSources = activeChunks.stream()
                .filter(chunk -> "url".equalsIgnoreCase(chunk.getSourceType()))
                .map(DocChunkEntity::getSourceId)
                .filter(sourceId -> sourceId != null && !sourceId.isBlank())
                .collect(Collectors.toSet());

        synchronized (lock) {
            return new Snapshot(
                    state.name().toLowerCase(Locale.ROOT),
                    lastStartAt,
                    lastSuccessAt,
                    lastError,
                    activeChunks.size(),
                    embeddingRepository.count(),
                    new SourceStats(localDocs.size(), urlSources.size()));
        }
    }

    public List<UrlSourceSnapshot> urlSources() {
        Map<String, RagSourceSyncStateEntity> stateBySourceId = sourceSyncStateRepository.findAll().stream()
                .collect(Collectors.toMap(RagSourceSyncStateEntity::getSourceId, item -> item, (left, right) -> left));

        return urlSourceProperties.getItems().stream()
                .filter(item -> item != null)
                .map(item -> {
                    String name = item.getName() == null || item.getName().isBlank() ? "unnamed" : item.getName().strip();
                    String sourceId = UrlSourceIdUtil.toSourceId(name, item.getUrl());
                    RagSourceSyncStateEntity state = stateBySourceId.get(sourceId);
                    return new UrlSourceSnapshot(
                            name,
                            item.getUrl(),
                            item.isEnabled(),
                            state == null ? "UNKNOWN" : state.getLastStatus(),
                            state == null ? null : state.getLastCheckedAt(),
                            state == null ? null : state.getLastError());
                })
                .toList();
    }

    public DocumentSnapshot documents() {
        List<DocChunkEntity> activeChunks = docChunkRepository.findByDeletedFalse();
        List<String> localDocs = activeChunks.stream()
                .filter(chunk -> "local_docs".equalsIgnoreCase(chunk.getSourceType()) || chunk.getSourceType() == null)
                .map(DocChunkEntity::getDocPath)
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .sorted()
                .toList();
        List<String> urlDocs = activeChunks.stream()
                .filter(chunk -> "url".equalsIgnoreCase(chunk.getSourceType()))
                .map(DocChunkEntity::getDocPath)
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .sorted()
                .toList();
        return new DocumentSnapshot(localDocs, urlDocs);
    }

    public boolean trigger() {
        return triggerInternal(SyncState.RUNNING, "manual");
    }

    public boolean triggerWarmup() {
        return triggerInternal(SyncState.WARMING, "bootstrap");
    }

    public boolean triggerScheduled() {
        return triggerInternal(SyncState.RUNNING, "scheduler");
    }

    private boolean triggerInternal(SyncState startState, String trigger) {
        synchronized (lock) {
            if (state == SyncState.RUNNING || state == SyncState.WARMING) {
                return false;
            }
            if (!vectorRagProperties.isEnabled()) {
                state = SyncState.FAILED;
                lastError = "vector rag is disabled";
                retrievalEventService.recordRagSync(null, 0, "failed", trigger, null, lastError);
                return false;
            }

            state = startState;
            lastStartAt = OffsetDateTime.now();
            lastError = null;
        }

        retrievalEventService.recordRagSync(null, 0, "started", trigger, null, null);

        try {
            taskExecutor.execute(() -> doSync(trigger));
            return true;
        } catch (RejectedExecutionException ex) {
            synchronized (lock) {
                state = SyncState.FAILED;
                lastError = "sync trigger rejected: " + ex.getMessage();
            }
            retrievalEventService.recordRagSync(null, 0, "failed", trigger, null, lastError);
            return false;
        } catch (RuntimeException ex) {
            synchronized (lock) {
                state = SyncState.FAILED;
                lastError = "sync trigger failed: " + ex.getMessage();
            }
            retrievalEventService.recordRagSync(null, 0, "failed", trigger, null, lastError);
            return false;
        }
    }

    private void doSync(String trigger) {
        try {
            Path docsRoot = Path.of(vectorRagProperties.getDocsRoot());
            VectorChunkSyncService.SyncSummary summary = vectorChunkSyncService.sync(docsRoot);
            synchronized (lock) {
                state = SyncState.COMPLETED;
                lastSuccessAt = OffsetDateTime.now();
                lastError = null;
            }
            retrievalEventService.recordRagSync(null, 0, "completed", trigger, summary, null);
        } catch (RuntimeException ex) {
            log.warn("RAG sync failed: trigger={}, message={}", trigger, ex.getMessage(), ex);
            synchronized (lock) {
                state = SyncState.FAILED;
                lastError = ex.getMessage();
            }
            retrievalEventService.recordRagSync(null, 0, "failed", trigger, null, ex.getMessage());
        }
    }

    public record Snapshot(
            String state,
            OffsetDateTime lastStartAt,
            OffsetDateTime lastSuccessAt,
            String lastError,
            long chunkCount,
            long embeddingCount,
            SourceStats sourceStats) {}

    public record SourceStats(long localDocs, long urlSources) {}

    public record UrlSourceSnapshot(
            String name,
            String url,
            boolean enabled,
            String lastStatus,
            OffsetDateTime lastCheckedAt,
            String lastError) {}

    public record DocumentSnapshot(List<String> localDocs, List<String> urlDocs) {}

    private enum SyncState {
        IDLE,
        WARMING,
        RUNNING,
        COMPLETED,
        FAILED
    }
}
