package com.mingming.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.repository.RunEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetrievalEventService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEventService.class);

    private static final int MAX_SNIPPET_CHARS = 200;
    private static final String DEFAULT_STRATEGY = "hybrid";
    private static final String DEFAULT_SOURCE = "hybrid";

    private final RunEventRepository runEventRepository;
    private final ObjectMapper objectMapper;

    public void record(UUID runId, int seq, String query, List<Bm25RetrieverService.RetrievalHit> hits) {
        List<RetrievalResultHit> enrichedHits = (hits == null ? List.<Bm25RetrieverService.RetrievalHit>of() : hits).stream()
                .map(hit -> new RetrievalResultHit(hit, DEFAULT_SOURCE))
                .toList();
        int hitCount = enrichedHits.size();
        record(runId, seq, query, new RetrievalMeta(DEFAULT_STRATEGY, hitCount, hitCount, hitCount), enrichedHits);
    }

    public void record(UUID runId, int seq, String query, RetrievalMeta retrievalMeta, List<RetrievalResultHit> hits) {
        if (runId == null || seq <= 0) {
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("query", query == null ? "" : query);

        List<RetrievalResultHit> safeHits = hits == null ? List.of() : hits;
        int fallbackHitCount = safeHits.size();
        RetrievalMeta safeMeta = retrievalMeta == null
                ? new RetrievalMeta(DEFAULT_STRATEGY, fallbackHitCount, fallbackHitCount, fallbackHitCount)
                : retrievalMeta;
        payload.put("strategy", normalizeStrategy(safeMeta.strategy()));
        payload.put("vectorHitCount", sanitizeCount(safeMeta.vectorHitCount(), fallbackHitCount));
        payload.put("bm25HitCount", sanitizeCount(safeMeta.bm25HitCount(), fallbackHitCount));
        payload.put("finalHitCount", sanitizeCount(safeMeta.finalHitCount(), fallbackHitCount));
        payload.put("hitCount", fallbackHitCount);

        ArrayNode topHits = payload.putArray("hits");
        for (RetrievalResultHit hit : safeHits) {
            if (hit == null || hit.hit() == null || hit.hit().chunk() == null) {
                continue;
            }
            Bm25RetrieverService.RetrievalHit retrievalHit = hit.hit();
            DocsChunk chunk = retrievalHit.chunk();
            ObjectNode item = topHits.addObject();
            item.put("chunkId", chunk.chunkId());
            item.put("docPath", chunk.docPath());
            item.put("headingPath", chunk.headingPath());
            item.put("snippet", toSnippet(chunk.content()));
            item.put("score", retrievalHit.score());
            item.put("source", normalizeSource(hit.source()));
        }

        RunEventEntity entity = new RunEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setRunId(runId);
        entity.setSeq(seq);
        entity.setType(RunEventType.RETRIEVAL_RESULT.name());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setPayload(payload.toString());
        runEventRepository.save(entity);
    }

    public void recordRagSync(
            UUID runId,
            int seq,
            String phase,
            String trigger,
            VectorChunkSyncService.SyncSummary summary,
            String errorMessage) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("phase", phase == null ? "unknown" : phase);
        payload.put("trigger", trigger == null ? "unknown" : trigger);
        payload.put("error", errorMessage == null ? "" : errorMessage);

        ObjectNode stats = payload.putObject("stats");
        stats.put("inserted", summary == null ? 0 : summary.inserted());
        stats.put("updated", summary == null ? 0 : summary.updated());
        stats.put("softDeleted", summary == null ? 0 : summary.softDeleted());
        stats.put("unchanged", summary == null ? 0 : summary.unchanged());

        if (runId == null || seq <= 0) {
            log.info("RAG_SYNC event: {}", payload);
            return;
        }

        RunEventEntity entity = new RunEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setRunId(runId);
        entity.setSeq(seq);
        entity.setType(RunEventType.RAG_SYNC.name());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setPayload(payload.toString());
        runEventRepository.save(entity);
    }

    private String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return DEFAULT_STRATEGY;
        }
        return strategy.strip().toLowerCase();
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return DEFAULT_SOURCE;
        }
        return source.strip().toLowerCase();
    }

    private int sanitizeCount(Integer count, int fallback) {
        if (count == null || count < 0) {
            return fallback;
        }
        return count;
    }

    private String toSnippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.strip();
        if (normalized.length() <= MAX_SNIPPET_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_SNIPPET_CHARS);
    }

    public record RetrievalMeta(String strategy, Integer vectorHitCount, Integer bm25HitCount, Integer finalHitCount) {}

    public record RetrievalResultHit(Bm25RetrieverService.RetrievalHit hit, String source) {}
}
