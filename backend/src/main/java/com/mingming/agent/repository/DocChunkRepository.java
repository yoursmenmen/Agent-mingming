package com.mingming.agent.repository;

import com.mingming.agent.entity.DocChunkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocChunkRepository extends JpaRepository<DocChunkEntity, String> {

    List<DocChunkEntity> findByDocPath(String docPath);

    List<DocChunkEntity> findBySourceIdAndDocPath(String sourceId, String docPath);

    List<DocChunkEntity> findByDocPathAndDeleted(String docPath, boolean deleted);

    List<DocChunkEntity> findByDeletedFalse();
}
