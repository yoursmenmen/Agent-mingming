package com.mingming.agent.rag;

import com.mingming.agent.entity.DocChunkEmbeddingEntity;
import com.mingming.agent.entity.DocChunkEntity;
import com.mingming.agent.repository.DocChunkEmbeddingRepository;
import com.mingming.agent.repository.DocChunkRepository;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VectorChunkSyncService {

    static final int EMBEDDING_DIMENSIONS = 1024;

    private final DocsChunkingService docsChunkingService;
    private final DocChunkRepository docChunkRepository;
    private final DocChunkEmbeddingRepository embeddingRepository;
    private final VectorRagProperties properties;
    private final EmbeddingModel embeddingModel;

    public VectorChunkSyncService(
            DocsChunkingService docsChunkingService,
            DocChunkRepository docChunkRepository,
            DocChunkEmbeddingRepository embeddingRepository,
            VectorRagProperties properties,
            EmbeddingModel embeddingModel) {
        this.docsChunkingService = docsChunkingService;
        this.docChunkRepository = docChunkRepository;
        this.embeddingRepository = embeddingRepository;
        this.properties = properties;
        this.embeddingModel = embeddingModel;
    }

    public record SyncSummary(int inserted, int updated, int softDeleted, int unchanged) {}

    @Transactional
    public SyncSummary sync(Path docsRoot) {
        if (!properties.isEnabled()) {
            return new SyncSummary(0, 0, 0, 0);
        }

        String model = properties.getEmbeddingModel();
        String version = properties.getEmbeddingVersion();

        List<DocsChunk> sourceChunks = docsChunkingService.loadChunks(docsRoot);
        Map<String, List<DocsChunk>> chunksByDocPath = sourceChunks.stream()
                .sorted(Comparator.comparing(DocsChunk::docPath).thenComparing(DocsChunk::chunkId))
                .collect(Collectors.groupingBy(DocsChunk::docPath, TreeMap::new, Collectors.toList()));
        Set<String> incomingDocPaths = chunksByDocPath.keySet();

        Map<String, List<DocChunkEntity>> activeChunksByDocPath = docChunkRepository.findByDeletedFalse().stream()
                .sorted(Comparator.comparing(DocChunkEntity::getDocPath).thenComparing(DocChunkEntity::getChunkId))
                .collect(Collectors.groupingBy(DocChunkEntity::getDocPath, TreeMap::new, Collectors.toList()));

        int inserted = 0;
        int updated = 0;
        int softDeleted = 0;
        int unchanged = 0;

        for (Map.Entry<String, List<DocsChunk>> entry : chunksByDocPath.entrySet()) {
            String docPath = entry.getKey();
            List<DocsChunk> docChunks = entry.getValue();

            List<DocChunkEntity> existingEntities = docChunkRepository.findByDocPath(docPath);
            Map<String, DocChunkEntity> existingByChunkId = existingEntities.stream()
                    .collect(Collectors.toMap(DocChunkEntity::getChunkId, entity -> entity, (left, right) -> left, LinkedHashMap::new));

            for (DocsChunk chunk : docChunks) {
                String contentHash = contentHash(chunk.content());
                DocChunkEntity existing = existingByChunkId.get(chunk.chunkId());

                if (existing == null) {
                    DocChunkEntity created = new DocChunkEntity();
                    applyChunk(created, chunk, contentHash, false);
                    docChunkRepository.save(created);
                    upsertEmbedding(chunk, model, version);
                    inserted++;
                    continue;
                }

                boolean chunkChanged = isChunkChanged(existing, chunk, contentHash);
                boolean embeddingChanged = isEmbeddingChanged(existing.getChunkId(), model, version);

                if (chunkChanged) {
                    applyChunk(existing, chunk, contentHash, false);
                    docChunkRepository.save(existing);
                    upsertEmbedding(chunk, model, version);
                    updated++;
                } else if (embeddingChanged) {
                    upsertEmbedding(chunk, model, version);
                    updated++;
                } else {
                    unchanged++;
                }
            }

            Set<String> incomingIds = docChunks.stream().map(DocsChunk::chunkId).collect(Collectors.toSet());
            List<DocChunkEntity> sortedExisting = existingEntities.stream()
                    .sorted(Comparator.comparing(DocChunkEntity::getChunkId))
                    .toList();
            for (DocChunkEntity existing : sortedExisting) {
                if (!incomingIds.contains(existing.getChunkId()) && !existing.isDeleted()) {
                    existing.setDeleted(true);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    docChunkRepository.save(existing);
                    softDeleted++;
                }
            }
        }

        for (Map.Entry<String, List<DocChunkEntity>> entry : activeChunksByDocPath.entrySet()) {
            if (incomingDocPaths.contains(entry.getKey())) {
                continue;
            }
            List<DocChunkEntity> activeChunks = entry.getValue().stream()
                    .sorted(Comparator.comparing(DocChunkEntity::getChunkId))
                    .toList();
            for (DocChunkEntity activeChunk : activeChunks) {
                activeChunk.setDeleted(true);
                activeChunk.setUpdatedAt(OffsetDateTime.now());
                docChunkRepository.save(activeChunk);
                softDeleted++;
            }
        }

        return new SyncSummary(inserted, updated, softDeleted, unchanged);
    }

    private boolean isChunkChanged(DocChunkEntity existing, DocsChunk incoming, String incomingHash) {
        if (existing.isDeleted()) {
            return true;
        }
        if (!Objects.equals(existing.getDocPath(), incoming.docPath())) {
            return true;
        }
        if (!Objects.equals(existing.getHeadingPath(), incoming.headingPath())) {
            return true;
        }
        if (!Objects.equals(existing.getContentHash(), incomingHash)) {
            return true;
        }
        return !Objects.equals(existing.getContent(), incoming.content());
    }

    private boolean isEmbeddingChanged(String chunkId, String model, String version) {
        return embeddingRepository
                .findByChunkId(chunkId)
                .map(embedding -> !Objects.equals(model, embedding.getEmbeddingModel())
                        || !Objects.equals(version, embedding.getEmbeddingVersion()))
                .orElse(true);
    }

    private void applyChunk(DocChunkEntity target, DocsChunk source, String contentHash, boolean deleted) {
        target.setChunkId(source.chunkId());
        target.setDocPath(source.docPath());
        target.setHeadingPath(source.headingPath());
        target.setContent(source.content());
        target.setContentHash(contentHash);
        target.setDeleted(deleted);
        target.setUpdatedAt(OffsetDateTime.now());
    }

    private void upsertEmbedding(DocsChunk chunk, String model, String version) {
        String embedding = toVectorLiteral(embeddingModel.embed(chunk.content() == null ? "" : chunk.content()));
        embeddingRepository.upsert(chunk.chunkId(), embedding, model, version);
    }

    private String contentHash(String content) {
        return sha256Hex(content == null ? "" : content);
    }

    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length != EMBEDDING_DIMENSIONS) {
            throw new IllegalStateException("Unexpected embedding dimension: "
                    + (embedding == null ? 0 : embedding.length)
                    + ", expected "
                    + EMBEDDING_DIMENSIONS);
        }

        StringBuilder builder = new StringBuilder(embedding.length * 10);
        builder.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(String.format(Locale.ROOT, "%.6f", embedding[i]));
        }
        builder.append(']');
        return builder.toString();
    }

    private String sha256Hex(String input) {
        return HexFormat.of().formatHex(sha256Bytes(input));
    }

    private byte[] sha256Bytes(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
