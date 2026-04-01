package com.mingming.agent.repository;

import com.mingming.agent.entity.RagSourceSyncStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagSourceSyncStateRepository extends JpaRepository<RagSourceSyncStateEntity, String> {}
