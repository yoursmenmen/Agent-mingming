package com.mingming.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mingming.agent.entity.DocChunkEntity;
import com.mingming.agent.rag.source.UrlSourceIdUtil;
import com.mingming.agent.rag.source.UrlSourceIngestionService;
import com.mingming.agent.repository.DocChunkEmbeddingRepository;
import com.mingming.agent.repository.DocChunkRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;

class VectorChunkSourceFieldsTest {

    @Test
    void sync_shouldPersistSourceTypeAndSourceIdForLocalAndUrlChunks() {
        DocsChunkingService docsChunkingService = Mockito.mock(DocsChunkingService.class);
        DocChunkRepository docChunkRepository = Mockito.mock(DocChunkRepository.class);
        DocChunkEmbeddingRepository embeddingRepository = Mockito.mock(DocChunkEmbeddingRepository.class);
        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        UrlSourceIngestionService urlSourceIngestionService = Mockito.mock(UrlSourceIngestionService.class);
        String urlSourceId = UrlSourceIdUtil.toSourceId("ref", "https://example.com/ref");

        when(docsChunkingService.loadChunks(Path.of("docs")))
                .thenReturn(List.of(new DocsChunk("local-1", "docs/a.md", "A", "local", 2)));
        when(urlSourceIngestionService.loadChunks())
                .thenReturn(List.of(new DocsChunk("url-1", "url/ref.md", "Ref", "url", 2, "url", urlSourceId)));
        when(docChunkRepository.findBySourceIdAndDocPath("local:docs/a.md", "docs/a.md")).thenReturn(List.of());
        when(docChunkRepository.findBySourceIdAndDocPath(urlSourceId, "url/ref.md")).thenReturn(List.of());
        when(embeddingModel.embed(anyString())).thenReturn(unitEmbedding());

        VectorRagProperties properties = new VectorRagProperties();
        VectorChunkSyncService service = new VectorChunkSyncService(
                docsChunkingService,
                docChunkRepository,
                embeddingRepository,
                properties,
                embeddingModel,
                urlSourceIngestionService,
                true);

        service.sync(Path.of("docs"));

        ArgumentCaptor<DocChunkEntity> captor = ArgumentCaptor.forClass(DocChunkEntity.class);
        verify(docChunkRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(DocChunkEntity::getSourceType)
                .containsExactlyInAnyOrder("local_docs", "url");
        assertThat(captor.getAllValues())
                .extracting(DocChunkEntity::getSourceId)
                .containsExactlyInAnyOrder("local:docs/a.md", urlSourceId);
    }

    private float[] unitEmbedding() {
        float[] values = new float[1024];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i % 5) / 5.0f;
        }
        return values;
    }
}
