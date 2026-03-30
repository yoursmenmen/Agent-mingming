package com.mingming.agent.rag;

public record DocsChunk(String chunkId, String docPath, String headingPath, String content, int tokenEstimate) {}
