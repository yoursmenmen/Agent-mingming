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
@Table(name = "doc_chunk")
public class DocChunkEntity {

    @Id
    @Column(name = "chunk_id", nullable = false, length = 64)
    private String chunkId;

    @Column(name = "doc_path", nullable = false, length = 512)
    private String docPath;

    @Column(name = "heading_path", nullable = false, length = 512)
    private String headingPath;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "content_hash", nullable = false, length = 128)
    private String contentHash;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
