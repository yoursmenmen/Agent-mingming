package com.mingming.agent.controller;

import com.mingming.agent.rag.SyncStatusService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RagSyncController {

    private final SyncStatusService syncStatusService;

    @GetMapping("/api/rag/sync/status")
    public Map<String, Object> getStatus() {
        return toPayload(syncStatusService.status());
    }

    @GetMapping("/api/rag/sources")
    public Map<String, Object> getSources() {
        return Map.of("sources", syncStatusService.urlSources());
    }

    @GetMapping("/api/rag/documents")
    public Map<String, Object> getDocuments() {
        SyncStatusService.DocumentSnapshot snapshot = syncStatusService.documents();
        return Map.of("localDocs", snapshot.localDocs(), "urlDocs", snapshot.urlDocs());
    }

    @PostMapping("/api/rag/sync/trigger")
    public Map<String, Object> trigger() {
        boolean accepted = syncStatusService.trigger();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accepted", accepted);
        payload.put("status", toPayload(syncStatusService.status()));
        return payload;
    }

    private Map<String, Object> toPayload(SyncStatusService.Snapshot snapshot) {
        Map<String, Object> sourceStats = new LinkedHashMap<>();
        sourceStats.put("localDocs", snapshot.sourceStats().localDocs());
        sourceStats.put("urlSources", snapshot.sourceStats().urlSources());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", snapshot.state());
        payload.put("lastStartAt", snapshot.lastStartAt());
        payload.put("lastSuccessAt", snapshot.lastSuccessAt());
        payload.put("lastError", snapshot.lastError());
        payload.put("chunkCount", snapshot.chunkCount());
        payload.put("embeddingCount", snapshot.embeddingCount());
        payload.put("sourceStats", sourceStats);
        return payload;
    }
}
