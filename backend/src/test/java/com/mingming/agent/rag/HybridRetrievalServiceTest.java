package com.mingming.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HybridRetrievalServiceTest {

    @Mock
    private Bm25RetrieverService bm25RetrieverService;

    @Mock
    private VectorRetrieverService vectorRetrieverService;

    @Test
    void search_shouldFuseBm25AndVectorByRrfAndRespectFinalTopN() {
        HybridRetrievalService service = new HybridRetrievalService(bm25RetrieverService, vectorRetrieverService);

        List<DocsChunk> bm25Corpus = List.of(chunk("a"), chunk("b"), chunk("c"));

        when(bm25RetrieverService.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(hit("a", 3.0), hit("b", 2.0), hit("c", 1.0)));
        when(vectorRetrieverService.search(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(hit("c", 0.9), hit("a", 0.8), hit("d", 0.7)));

        List<Bm25RetrieverService.RetrievalHit> results = service.search("query", bm25Corpus, 3, 0.0D, 3, 0.0D, 3);

        assertThat(results).extracting(hit -> hit.chunk().chunkId()).containsExactly("a", "c", "b");
    }

    @Test
    void searchWithObservability_shouldExposeStrategyCountsAndPerHitSource() {
        HybridRetrievalService service = new HybridRetrievalService(bm25RetrieverService, vectorRetrieverService);

        List<DocsChunk> bm25Corpus = List.of(chunk("a"), chunk("b"), chunk("c"));

        when(bm25RetrieverService.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(hit("a", 3.0), hit("b", 2.0), hit("c", 1.0)));
        when(vectorRetrieverService.search(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of(hit("c", 0.9), hit("a", 0.8), hit("d", 0.7)));

        HybridRetrievalService.RetrievalResult result =
                service.searchWithObservability("query", bm25Corpus, 3, 0.0D, 3, 0.0D, 3);

        assertThat(result.strategy()).isEqualTo("hybrid");
        assertThat(result.bm25HitCount()).isEqualTo(3);
        assertThat(result.vectorHitCount()).isEqualTo(3);
        assertThat(result.finalHitCount()).isEqualTo(3);
        assertThat(result.hits()).extracting(hit -> hit.hit().chunk().chunkId()).containsExactly("a", "c", "b");
        assertThat(result.hits()).extracting(HybridRetrievalService.RetrievalResultHit::source).containsExactly("hybrid", "hybrid", "bm25");
    }

    @Test
    void search_shouldDegradeToBm25WhenVectorFails() {
        HybridRetrievalService service = new HybridRetrievalService(bm25RetrieverService, vectorRetrieverService);

        List<DocsChunk> bm25Corpus = List.of(chunk("a"), chunk("b"), chunk("c"));

        when(bm25RetrieverService.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(hit("b", 2.0), hit("a", 1.0), hit("c", 0.5)));
        when(vectorRetrieverService.search(anyString(), anyInt(), anyDouble()))
                .thenThrow(new IllegalStateException("pgvector unavailable"));

        List<Bm25RetrieverService.RetrievalHit> results = service.search("query", bm25Corpus, 5, 0.0D, 5, 0.0D, 2);

        assertThat(results).extracting(hit -> hit.chunk().chunkId()).containsExactly("b", "a");
        verify(vectorRetrieverService).search("query", 5, 0.0D);
    }

    @Test
    void searchWithObservability_shouldUseBm25StrategyWhenVectorFails() {
        HybridRetrievalService service = new HybridRetrievalService(bm25RetrieverService, vectorRetrieverService);

        when(bm25RetrieverService.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(hit("b", 2.0), hit("a", 1.0), hit("c", 0.5)));
        when(vectorRetrieverService.search(anyString(), anyInt(), anyDouble()))
                .thenThrow(new IllegalStateException("pgvector unavailable"));

        HybridRetrievalService.RetrievalResult result =
                service.searchWithObservability("query", List.of(chunk("a"), chunk("b"), chunk("c")), 5, 0.0D, 5, 0.0D, 2);

        assertThat(result.strategy()).isEqualTo("bm25");
        assertThat(result.vectorHitCount()).isEqualTo(0);
        assertThat(result.bm25HitCount()).isEqualTo(3);
        assertThat(result.finalHitCount()).isEqualTo(2);
        assertThat(result.hits()).extracting(hit -> hit.hit().chunk().chunkId()).containsExactly("b", "a");
        assertThat(result.hits()).extracting(HybridRetrievalService.RetrievalResultHit::source).containsExactly("bm25", "bm25");
    }

    @Test
    void search_shouldBreakTiesDeterministicallyByChunkId() {
        HybridRetrievalService service = new HybridRetrievalService(bm25RetrieverService, vectorRetrieverService);

        when(bm25RetrieverService.search(anyString(), any(), anyInt(), anyDouble())).thenReturn(List.of(hit("b", 1.0)));
        when(vectorRetrieverService.search(anyString(), anyInt(), anyDouble())).thenReturn(List.of(hit("a", 1.0)));

        List<Bm25RetrieverService.RetrievalHit> results = service.search("query", List.of(chunk("a"), chunk("b")), 5, 0.0D, 5, 0.0D, 2);

        assertThat(results).extracting(hit -> hit.chunk().chunkId()).containsExactly("a", "b");
    }

    private static Bm25RetrieverService.RetrievalHit hit(String chunkId, double score) {
        return new Bm25RetrieverService.RetrievalHit(chunk(chunkId), score);
    }

    private static DocsChunk chunk(String chunkId) {
        return new DocsChunk(chunkId, "docs/" + chunkId + ".md", "Heading-" + chunkId, "Content-" + chunkId, 12);
    }
}
