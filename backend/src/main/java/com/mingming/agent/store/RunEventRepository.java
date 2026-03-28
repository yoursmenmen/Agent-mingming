package com.mingming.agent.store;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunEventRepository extends JpaRepository<RunEventEntity, UUID> {
    List<RunEventEntity> findByRunIdOrderBySeqAsc(UUID runId);
}
