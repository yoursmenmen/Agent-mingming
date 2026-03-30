package com.mingming.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.repository.RunEventRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RetrievalEventService {

    private static final int MAX_SNIPPET_CHARS = 200;

    private final RunEventRepository runEventRepository;
    private final ObjectMapper objectMapper;

    public void record(UUID runId, int seq, String query, List<Bm25RetrieverService.RetrievalHit> hits) {
        if (runId == null || seq <= 0) {
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("query", query == null ? "" : query);

        List<Bm25RetrieverService.RetrievalHit> safeHits = hits == null ? List.of() : hits;
        payload.put("hitCount", safeHits.size());

        ArrayNode topHits = payload.putArray("hits");
        for (Bm25RetrieverService.RetrievalHit hit : safeHits) {
            if (hit == null || hit.chunk() == null) {
                continue;
            }
            DocsChunk chunk = hit.chunk();
            ObjectNode item = topHits.addObject();
            item.put("chunkId", chunk.chunkId());
            item.put("docPath", chunk.docPath());
            item.put("headingPath", chunk.headingPath());
            item.put("snippet", toSnippet(chunk.content()));
            item.put("score", hit.score());
        }

        RunEventEntity entity = new RunEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setRunId(runId);
        entity.setSeq(seq);
        entity.setType(RunEventType.RETRIEVAL_RESULT.name());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setPayload(payload.toString());
        runEventRepository.save(entity);
    }

    private String toSnippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.strip();
        if (normalized.length() <= MAX_SNIPPET_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_SNIPPET_CHARS);
    }
}
