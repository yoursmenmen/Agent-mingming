package com.mingming.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.mingming.agent.entity.DocChunkEmbeddingEntity;
import com.mingming.agent.entity.DocChunkEntity;
import com.mingming.agent.repository.DocChunkEmbeddingRepository;
import com.mingming.agent.repository.DocChunkRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;

class VectorChunkSyncServiceTest {

    private final DocsChunkingService docsChunkingService = Mockito.mock(DocsChunkingService.class);
    private final DocChunkRepository docChunkRepository = Mockito.mock(DocChunkRepository.class);
    private final DocChunkEmbeddingRepository embeddingRepository = Mockito.mock(DocChunkEmbeddingRepository.class);
    private final EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);

    @Test
    void sync_shouldInsertNewChunkAndEmbedding() {
        when(docsChunkingService.loadChunks(Path.of("docs")))
                .thenReturn(List.of(new DocsChunk("c1", "docs/a.md", "A", "new content", 8)));
        when(docChunkRepository.findByDocPath("docs/a.md")).thenReturn(List.of());

        VectorChunkSyncService service = newService("text-embedding-v3", "2026-03");

        VectorChunkSyncService.SyncSummary summary = service.sync(Path.of("docs"));

        assertThat(summary.inserted()).isEqualTo(1);
        assertThat(summary.updated()).isEqualTo(0);
        assertThat(summary.softDeleted()).isEqualTo(0);
        assertThat(summary.unchanged()).isEqualTo(0);

        ArgumentCaptor<DocChunkEntity> savedChunk = ArgumentCaptor.forClass(DocChunkEntity.class);
        verify(docChunkRepository).save(savedChunk.capture());
        assertThat(savedChunk.getValue().getChunkId()).isEqualTo("c1");
        assertThat(savedChunk.getValue().getDocPath()).isEqualTo("docs/a.md");
        assertThat(savedChunk.getValue().isDeleted()).isFalse();
        assertThat(savedChunk.getValue().getContentHash()).isNotBlank();

        verify(embeddingRepository).upsert(eq("c1"), any(String.class), eq("text-embedding-v3"), eq("2026-03"));
    }

    @Test
    void sync_shouldUpdateChunkWhenContentHashChanged() {
        DocsChunk changed = new DocsChunk("c1", "docs/a.md", "A", "updated", 4);
        when(docsChunkingService.loadChunks(Path.of("docs"))).thenReturn(List.of(changed));

        DocChunkEntity existing = chunkEntity("c1", "docs/a.md", "A", "old", "old-hash", false);
        when(docChunkRepository.findByDocPath("docs/a.md")).thenReturn(List.of(existing));

        VectorChunkSyncService service = newService("text-embedding-v3", "2026-03");

        VectorChunkSyncService.SyncSummary summary = service.sync(Path.of("docs"));

        assertThat(summary.inserted()).isEqualTo(0);
        assertThat(summary.updated()).isEqualTo(1);
        assertThat(summary.softDeleted()).isEqualTo(0);

        ArgumentCaptor<DocChunkEntity> savedChunk = ArgumentCaptor.forClass(DocChunkEntity.class);
        verify(docChunkRepository).save(savedChunk.capture());
        assertThat(savedChunk.getValue().getContent()).isEqualTo("updated");
        assertThat(savedChunk.getValue().getContentHash()).isNotEqualTo("old-hash");
        verify(embeddingRepository).upsert(eq("c1"), any(String.class), eq("text-embedding-v3"), eq("2026-03"));
    }

    @Test
    void sync_shouldRefreshEmbeddingWhenModelVersionChanged() {
        DocsChunk same = new DocsChunk("c1", "docs/a.md", "A", "same", 4);
        when(docsChunkingService.loadChunks(Path.of("docs"))).thenReturn(List.of(same));

        DocChunkEntity existingChunk = chunkEntity("c1", "docs/a.md", "A", "same", hashOf("same"), false);
        when(docChunkRepository.findByDocPath("docs/a.md")).thenReturn(List.of(existingChunk));

        DocChunkEmbeddingEntity existingEmbedding = new DocChunkEmbeddingEntity();
        existingEmbedding.setChunkId("c1");
        existingEmbedding.setEmbeddingModel("old-model");
        existingEmbedding.setEmbeddingVersion("old-version");
        when(embeddingRepository.findByChunkId("c1")).thenReturn(java.util.Optional.of(existingEmbedding));

        VectorChunkSyncService service = newService("new-model", "new-version");

        VectorChunkSyncService.SyncSummary summary = service.sync(Path.of("docs"));

        assertThat(summary.updated()).isEqualTo(1);
        verify(docChunkRepository, never()).save(any(DocChunkEntity.class));
        verify(embeddingRepository).upsert(eq("c1"), any(String.class), eq("new-model"), eq("new-version"));
    }

    @Test
    void sync_shouldSoftDeleteMissingChunksForSameDocPath() {
        when(docsChunkingService.loadChunks(Path.of("docs")))
                .thenReturn(List.of(new DocsChunk("c1", "docs/a.md", "A", "stay", 4)));

        DocChunkEntity kept = chunkEntity("c1", "docs/a.md", "A", "stay", hashOf("stay"), false);
        DocChunkEntity removed = chunkEntity("c2", "docs/a.md", "A", "gone", "hash-gone", false);
        when(docChunkRepository.findByDocPath("docs/a.md")).thenReturn(List.of(kept, removed));

        VectorChunkSyncService service = newService("text-embedding-v3", "2026-03");

        VectorChunkSyncService.SyncSummary summary = service.sync(Path.of("docs"));

        assertThat(summary.softDeleted()).isEqualTo(1);

        ArgumentCaptor<DocChunkEntity> savedChunk = ArgumentCaptor.forClass(DocChunkEntity.class);
        verify(docChunkRepository).save(savedChunk.capture());
        assertThat(savedChunk.getValue().getChunkId()).isEqualTo("c2");
        assertThat(savedChunk.getValue().isDeleted()).isTrue();
    }

    @Test
    void sync_shouldBeIdempotentWhenChunksAndEmbeddingsAlreadyMatch() {
        when(docsChunkingService.loadChunks(Path.of("docs")))
                .thenReturn(List.of(new DocsChunk("c1", "docs/a.md", "A", "same", 4)));

        DocChunkEntity existingChunk = chunkEntity("c1", "docs/a.md", "A", "same", hashOf("same"), false);
        when(docChunkRepository.findByDocPath("docs/a.md")).thenReturn(List.of(existingChunk));

        DocChunkEmbeddingEntity existingEmbedding = new DocChunkEmbeddingEntity();
        existingEmbedding.setChunkId("c1");
        existingEmbedding.setEmbeddingModel("text-embedding-v3");
        existingEmbedding.setEmbeddingVersion("2026-03");
        when(embeddingRepository.findByChunkId("c1")).thenReturn(java.util.Optional.of(existingEmbedding));

        VectorChunkSyncService service = newService("text-embedding-v3", "2026-03");

        VectorChunkSyncService.SyncSummary summary = service.sync(Path.of("docs"));

        assertThat(summary.inserted()).isEqualTo(0);
        assertThat(summary.updated()).isEqualTo(0);
        assertThat(summary.softDeleted()).isEqualTo(0);
        assertThat(summary.unchanged()).isEqualTo(1);
        verify(docChunkRepository, never()).save(any(DocChunkEntity.class));
        verify(embeddingRepository, never()).upsert(any(String.class), any(String.class), any(String.class), any(String.class));
    }

    @Test
    void sync_shouldSoftDeleteAllActiveChunksWhenWholeDocPathDisappears() {
        when(docsChunkingService.loadChunks(Path.of("docs"))).thenReturn(List.of());

        DocChunkEntity oldA = chunkEntity("gone-1", "docs/removed.md", "A", "old-a", hashOf("old-a"), false);
        DocChunkEntity oldB = chunkEntity("gone-2", "docs/removed.md", "B", "old-b", hashOf("old-b"), false);
        when(docChunkRepository.findByDeletedFalse()).thenReturn(List.of(oldA, oldB));

        VectorChunkSyncService service = newService("text-embedding-v3", "2026-03");

        VectorChunkSyncService.SyncSummary summary = service.sync(Path.of("docs"));

        assertThat(summary.softDeleted()).isEqualTo(2);
        assertThat(summary.inserted()).isEqualTo(0);
        assertThat(summary.updated()).isEqualTo(0);

        ArgumentCaptor<DocChunkEntity> savedChunk = ArgumentCaptor.forClass(DocChunkEntity.class);
        verify(docChunkRepository, times(2)).save(savedChunk.capture());
        assertThat(savedChunk.getAllValues()).extracting(DocChunkEntity::getChunkId).containsExactly("gone-1", "gone-2");
        assertThat(savedChunk.getAllValues()).allSatisfy(entity -> assertThat(entity.isDeleted()).isTrue());
    }

    @Test
    void sync_shouldGenerateFixed1024DimensionEmbedding() {
        when(docsChunkingService.loadChunks(Path.of("docs")))
                .thenReturn(List.of(new DocsChunk("c-fixed", "docs/fixed.md", "H", "content", 4)));
        when(docChunkRepository.findByDocPath("docs/fixed.md")).thenReturn(List.of());

        VectorChunkSyncService service = newService("text-embedding-v3", "2026-03");

        service.sync(Path.of("docs"));

        ArgumentCaptor<String> embeddingCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingRepository).upsert(eq("c-fixed"), embeddingCaptor.capture(), eq("text-embedding-v3"), eq("2026-03"));

        String vectorLiteral = embeddingCaptor.getValue();
        String body = vectorLiteral.substring(1, vectorLiteral.length() - 1);
        assertThat(body.split(",")).hasSize(1024);
    }

    private VectorChunkSyncService newService(String embeddingModel, String embeddingVersion) {
        VectorRagProperties properties = new VectorRagProperties();
        properties.setEmbeddingModel(embeddingModel);
        properties.setEmbeddingVersion(embeddingVersion);
        when(this.embeddingModel.embed(anyString())).thenReturn(unitEmbedding());
        return new VectorChunkSyncService(docsChunkingService, docChunkRepository, embeddingRepository, properties, this.embeddingModel);
    }

    private float[] unitEmbedding() {
        float[] values = new float[1024];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i % 7) / 7.0f;
        }
        return values;
    }

    private DocChunkEntity chunkEntity(
            String chunkId, String docPath, String headingPath, String content, String contentHash, boolean deleted) {
        DocChunkEntity entity = new DocChunkEntity();
        entity.setChunkId(chunkId);
        entity.setDocPath(docPath);
        entity.setHeadingPath(headingPath);
        entity.setContent(content);
        entity.setContentHash(contentHash);
        entity.setDeleted(deleted);
        entity.setUpdatedAt(OffsetDateTime.now());
        return entity;
    }

    private String hashOf(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void shouldMapChunkAndEmbeddingEntities() {
        DocChunkEntity chunk = new DocChunkEntity();
        chunk.setChunkId("c1");
        chunk.setDocPath("docs/a.md");
        chunk.setDeleted(false);

        assertThat(chunk.getChunkId()).isEqualTo("c1");
        assertThat(chunk.getDocPath()).isEqualTo("docs/a.md");
        assertThat(chunk.isDeleted()).isFalse();

        DocChunkEmbeddingEntity embedding = new DocChunkEmbeddingEntity();
        embedding.setChunkId("c1");
        embedding.setEmbeddingModel("text-embedding-v3");

        assertThat(embedding.getChunkId()).isEqualTo("c1");
        assertThat(embedding.getEmbeddingModel()).isEqualTo("text-embedding-v3");
    }
}
