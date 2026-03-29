package com.mingming.agent.repository;

import com.mingming.agent.entity.AgentRunEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, UUID> {
    List<AgentRunEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
