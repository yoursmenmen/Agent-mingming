package com.mingming.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.repository.RunEventRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetrievalEventServiceTest {

    @Mock
    private RunEventRepository runEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void record_shouldPersistRetrievalEventWhenNoHits() throws Exception {
        RetrievalEventService service = new RetrievalEventService(runEventRepository, objectMapper);
        UUID runId = UUID.randomUUID();

        service.record(runId, 2, "无命中查询", List.of());

        ArgumentCaptor<RunEventEntity> captor = ArgumentCaptor.forClass(RunEventEntity.class);
        verify(runEventRepository).save(captor.capture());

        RunEventEntity saved = captor.getValue();
        assertThat(saved.getRunId()).isEqualTo(runId);
        assertThat(saved.getSeq()).isEqualTo(2);
        assertThat(saved.getType()).isEqualTo("RETRIEVAL_RESULT");

        JsonNode payload = objectMapper.readTree(saved.getPayload());
        assertThat(payload.path("query").asText()).isEqualTo("无命中查询");
        assertThat(payload.path("hitCount").asInt()).isEqualTo(0);
        assertThat(payload.path("strategy").asText()).isEqualTo("hybrid");
        assertThat(payload.path("vectorHitCount").asInt()).isEqualTo(0);
        assertThat(payload.path("bm25HitCount").asInt()).isEqualTo(0);
        assertThat(payload.path("finalHitCount").asInt()).isEqualTo(0);
        assertThat(payload.path("hits").isArray()).isTrue();
        assertThat(payload.path("hits")).isEmpty();
    }

    @Test
    void record_shouldPersistMetadataAndSnippetInsteadOfFullContentForMultipleHits() throws Exception {
        RetrievalEventService service = new RetrievalEventService(runEventRepository, objectMapper);
        UUID runId = UUID.randomUUID();
        String longContent = "A".repeat(400);

        List<Bm25RetrieverService.RetrievalHit> hits = List.of(
                new Bm25RetrieverService.RetrievalHit(
                        new DocsChunk("chunk-1", "docs/one.md", "H1", "short content", 6),
                        1.5),
                new Bm25RetrieverService.RetrievalHit(
                        new DocsChunk("chunk-2", "docs/two.md", "H2", longContent, 200),
                        0.9));

        service.record(runId, 3, "多命中查询", hits);

        ArgumentCaptor<RunEventEntity> captor = ArgumentCaptor.forClass(RunEventEntity.class);
        verify(runEventRepository).save(captor.capture());

        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.path("hitCount").asInt()).isEqualTo(2);
        assertThat(payload.path("strategy").asText()).isEqualTo("hybrid");
        assertThat(payload.path("vectorHitCount").asInt()).isEqualTo(2);
        assertThat(payload.path("bm25HitCount").asInt()).isEqualTo(2);
        assertThat(payload.path("finalHitCount").asInt()).isEqualTo(2);
        JsonNode persistedHits = payload.path("hits");
        assertThat(persistedHits).hasSize(2);

        JsonNode first = persistedHits.get(0);
        assertThat(first.path("chunkId").asText()).isEqualTo("chunk-1");
        assertThat(first.path("docPath").asText()).isEqualTo("docs/one.md");
        assertThat(first.path("headingPath").asText()).isEqualTo("H1");
        assertThat(first.path("snippet").asText()).isEqualTo("short content");
        assertThat(first.path("source").asText()).isEqualTo("hybrid");
        assertThat(first.has("content")).isFalse();

        JsonNode second = persistedHits.get(1);
        assertThat(second.path("chunkId").asText()).isEqualTo("chunk-2");
        assertThat(second.path("snippet").asText()).startsWith("A");
        assertThat(second.path("snippet").asText().length()).isLessThan(longContent.length());
        assertThat(second.path("source").asText()).isEqualTo("hybrid");
        assertThat(second.has("content")).isFalse();
    }

    @Test
    void record_shouldUseExplicitRetrievalMetaAndHitSourceWhenProvided() throws Exception {
        RetrievalEventService service = new RetrievalEventService(runEventRepository, objectMapper);
        UUID runId = UUID.randomUUID();

        List<RetrievalEventService.RetrievalResultHit> hits = List.of(new RetrievalEventService.RetrievalResultHit(
                new Bm25RetrieverService.RetrievalHit(new DocsChunk("chunk-v", "docs/vector.md", "Vector", "vector content", 4), 0.83),
                "vector"));

        service.record(
                runId,
                4,
                "向量检索",
                new RetrievalEventService.RetrievalMeta("vector", 5, 0, 1),
                hits);

        ArgumentCaptor<RunEventEntity> captor = ArgumentCaptor.forClass(RunEventEntity.class);
        verify(runEventRepository).save(captor.capture());

        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertThat(payload.path("strategy").asText()).isEqualTo("vector");
        assertThat(payload.path("vectorHitCount").asInt()).isEqualTo(5);
        assertThat(payload.path("bm25HitCount").asInt()).isEqualTo(0);
        assertThat(payload.path("finalHitCount").asInt()).isEqualTo(1);
        assertThat(payload.path("hits")).hasSize(1);
        assertThat(payload.path("hits").get(0).path("source").asText()).isEqualTo("vector");
    }
}
