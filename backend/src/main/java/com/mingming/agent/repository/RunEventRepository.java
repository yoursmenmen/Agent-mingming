package com.mingming.agent.repository;

import com.mingming.agent.entity.RunEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunEventRepository extends JpaRepository<RunEventEntity, UUID> {
    List<RunEventEntity> findByRunIdOrderBySeqAsc(UUID runId);

    List<RunEventEntity> findByRunIdInOrderByCreatedAtAscSeqAsc(List<UUID> runIds);
}
