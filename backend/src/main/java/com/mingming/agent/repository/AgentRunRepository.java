package com.mingming.agent.repository;

import com.mingming.agent.entity.AgentRunEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, UUID> {}
