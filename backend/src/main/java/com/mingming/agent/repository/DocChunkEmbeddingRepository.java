package com.mingming.agent.repository;

import com.mingming.agent.entity.DocChunkEmbeddingEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DocChunkEmbeddingRepository extends JpaRepository<DocChunkEmbeddingEntity, String> {

    Optional<DocChunkEmbeddingEntity> findByChunkId(String chunkId);

    @Modifying
    @Transactional
    @Query(
            value =
                    """
                    INSERT INTO doc_chunk_embedding (chunk_id, embedding, embedding_model, embedding_version, updated_at)
                    VALUES (:chunkId, CAST(:embedding AS vector), :embeddingModel, :embeddingVersion, now())
                    ON CONFLICT (chunk_id)
                    DO UPDATE
                      SET embedding = EXCLUDED.embedding,
                          embedding_model = EXCLUDED.embedding_model,
                          embedding_version = EXCLUDED.embedding_version,
                          updated_at = now()
                    """,
            nativeQuery = true)
    void upsert(
            @Param("chunkId") String chunkId,
            @Param("embedding") String embedding,
            @Param("embeddingModel") String embeddingModel,
            @Param("embeddingVersion") String embeddingVersion);
}
