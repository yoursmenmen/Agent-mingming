package com.mingming.agent.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);
    private static final int RRF_K = 60;

    private final Bm25RetrieverService bm25RetrieverService;
    private final VectorRetrieverService vectorRetrieverService;

    public HybridRetrievalService(Bm25RetrieverService bm25RetrieverService, VectorRetrieverService vectorRetrieverService) {
        this.bm25RetrieverService = bm25RetrieverService;
        this.vectorRetrieverService = vectorRetrieverService;
    }

    public List<Bm25RetrieverService.RetrievalHit> search(
            String query,
            List<DocsChunk> bm25Corpus,
            int bm25TopK,
            double bm25Threshold,
            int vectorTopK,
            double vectorThreshold,
            int finalTopN) {
        return searchWithObservability(
                        query,
                        bm25Corpus,
                        bm25TopK,
                        bm25Threshold,
                        vectorTopK,
                        vectorThreshold,
                        finalTopN)
                .hits().stream()
                .map(RetrievalResultHit::hit)
                .toList();
    }

    public RetrievalResult searchWithObservability(
            String query,
            List<DocsChunk> bm25Corpus,
            int bm25TopK,
            double bm25Threshold,
            int vectorTopK,
            double vectorThreshold,
            int finalTopN) {
        if (query == null || query.isBlank() || finalTopN <= 0) {
            return new RetrievalResult("hybrid", 0, 0, 0, List.of());
        }

        List<Bm25RetrieverService.RetrievalHit> bm25Hits = bm25RetrieverService.search(query, bm25Corpus, bm25TopK, bm25Threshold);

        List<Bm25RetrieverService.RetrievalHit> vectorHits;
        try {
            vectorHits = vectorRetrieverService.search(query, vectorTopK, vectorThreshold);
        } catch (RuntimeException ex) {
            log.warn(
                    "RAG retrieval degraded: vector search failed; queryLength={}, queryHash={}, exceptionType={}, message={}",
                    query.length(),
                    Integer.toHexString(query.hashCode()),
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex);
            List<RetrievalResultHit> fallbackHits = bm25Hits.stream()
                    .limit(finalTopN)
                    .map(hit -> new RetrievalResultHit(hit, "bm25"))
                    .toList();
            return new RetrievalResult("bm25", 0, bm25Hits.size(), fallbackHits.size(), fallbackHits);
        }

        if (vectorHits == null || vectorHits.isEmpty()) {
            List<RetrievalResultHit> fallbackHits = bm25Hits.stream()
                    .limit(finalTopN)
                    .map(hit -> new RetrievalResultHit(hit, "bm25"))
                    .toList();
            return new RetrievalResult("bm25", 0, bm25Hits.size(), fallbackHits.size(), fallbackHits);
        }

        Map<String, FusedHit> fused = new LinkedHashMap<>();
        applyRrf(fused, bm25Hits, true);
        applyRrf(fused, vectorHits, false);

        List<RetrievalResultHit> fusedHits = fused.values().stream()
                .sorted((left, right) -> {
                    int scoreOrder = Double.compare(right.score(), left.score());
                    if (scoreOrder != 0) {
                        return scoreOrder;
                    }
                    return left.chunk().chunkId().compareTo(right.chunk().chunkId());
                })
                .limit(finalTopN)
                .map(hit -> new RetrievalResultHit(
                        new Bm25RetrieverService.RetrievalHit(hit.chunk(), hit.score()),
                        resolveSource(hit.fromBm25(), hit.fromVector())))
                .toList();

        String strategy = bm25Hits.isEmpty() ? "vector" : "hybrid";
        return new RetrievalResult(strategy, vectorHits.size(), bm25Hits.size(), fusedHits.size(), fusedHits);
    }

    private void applyRrf(Map<String, FusedHit> fused, List<Bm25RetrieverService.RetrievalHit> rankedHits, boolean fromBm25) {
        if (rankedHits == null || rankedHits.isEmpty()) {
            return;
        }
        for (int i = 0; i < rankedHits.size(); i++) {
            Bm25RetrieverService.RetrievalHit hit = rankedHits.get(i);
            if (hit == null || hit.chunk() == null || hit.chunk().chunkId() == null) {
                continue;
            }

            int rank = i + 1;
            double rrfScore = 1.0D / (RRF_K + rank);
            FusedHit existing = fused.get(hit.chunk().chunkId());
            if  (existing == null) {
                fused.put(hit.chunk().chunkId(), new FusedHit(hit.chunk(), rrfScore, fromBm25, !fromBm25));
                continue;
            }
            fused.put(
                    hit.chunk().chunkId(),
                    new FusedHit(
                            existing.chunk(),
                            existing.score() + rrfScore,
                            existing.fromBm25() || fromBm25,
                            existing.fromVector() || !fromBm25));
        }
    }

    private String resolveSource(boolean fromBm25, boolean fromVector) {
        if (fromBm25 && fromVector) {
            return "hybrid";
        }
        if (fromBm25) {
            return "bm25";
        }
        return "vector";
    }

    private record FusedHit(DocsChunk chunk, double score, boolean fromBm25, boolean fromVector) {}

    public record RetrievalResult(
            String strategy,
            int vectorHitCount,
            int bm25HitCount,
            int finalHitCount,
            List<RetrievalResultHit> hits) {}

    public record RetrievalResultHit(Bm25RetrieverService.RetrievalHit hit, String source) {}
}
