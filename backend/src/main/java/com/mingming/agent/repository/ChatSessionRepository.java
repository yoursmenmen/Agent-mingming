package com.mingming.agent.repository;

import com.mingming.agent.entity.ChatSessionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, UUID> {}
