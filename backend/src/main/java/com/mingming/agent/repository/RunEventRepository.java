package com.mingming.agent.repository;

import com.mingming.agent.entity.RunEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunEventRepository extends JpaRepository<RunEventEntity, UUID> {
    List<RunEventEntity> findByRunIdOrderBySeqAsc(UUID runId);

    List<RunEventEntity> findByRunIdInOrderByCreatedAtAscSeqAsc(List<UUID> runIds);

    @Query("SELECT COALESCE(MAX(e.seq), 0) FROM RunEventEntity e WHERE e.runId = :runId")
    Integer findMaxSeqByRunId(@Param("runId") UUID runId);

    @Query(
            value = """
                    SELECT e.*
                    FROM run_event e
                    JOIN agent_run r ON r.id = e.run_id
                    WHERE r.session_id = :sessionId
                      AND e.type IN ('USER_MESSAGE', 'MODEL_MESSAGE')
                    ORDER BY e.created_at DESC, e.seq DESC
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<RunEventEntity> findRecentConversationEvents(@Param("sessionId") UUID sessionId, @Param("limit") int limit);
}
