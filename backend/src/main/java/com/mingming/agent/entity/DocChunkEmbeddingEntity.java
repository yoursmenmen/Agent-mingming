package com.mingming.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "doc_chunk_embedding")
public class DocChunkEmbeddingEntity {

    @Id
    @Column(name = "chunk_id", nullable = false, length = 64)
    private String chunkId;

    @Column(name = "embedding", nullable = false, columnDefinition = "vector(1024)")
    private String embedding;

    @Column(name = "embedding_model", nullable = false, length = 128)
    private String embeddingModel;

    @Column(name = "embedding_version", nullable = false, length = 64)
    private String embeddingVersion;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
