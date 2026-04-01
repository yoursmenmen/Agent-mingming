package com.mingming.agent.rag;

public record DocsChunk(
        String chunkId,
        String docPath,
        String headingPath,
        String content,
        int tokenEstimate,
        String sourceType,
        String sourceId) {

    public DocsChunk(String chunkId, String docPath, String headingPath, String content, int tokenEstimate) {
        this(chunkId, docPath, headingPath, content, tokenEstimate, "local_docs", "local:" + (docPath == null ? "unknown" : docPath));
    }
}
