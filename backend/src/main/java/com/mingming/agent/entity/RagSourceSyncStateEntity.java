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
@Table(name = "rag_source_sync_state")
public class RagSourceSyncStateEntity {

    @Id
    @Column(name = "source_id", nullable = false, length = 256)
    private String sourceId;

    @Column(name = "etag", length = 512)
    private String etag;

    @Column(name = "last_modified", length = 128)
    private String lastModified;

    @Column(name = "last_doc_hash", length = 128)
    private String lastDocHash;

    @Column(name = "last_checked_at", nullable = false)
    private OffsetDateTime lastCheckedAt;

    @Column(name = "last_success_at")
    private OffsetDateTime lastSuccessAt;

    @Column(name = "last_status", nullable = false, length = 64)
    private String lastStatus;

    @Column(name = "last_error")
    private String lastError;
}
