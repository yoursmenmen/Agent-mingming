package com.mingming.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "run_event")
public class RunEventEntity {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String payload;
}
