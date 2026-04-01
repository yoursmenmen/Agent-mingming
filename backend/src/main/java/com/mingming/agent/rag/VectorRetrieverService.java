package com.mingming.agent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VectorRetrieverService {

    private static final int EMBEDDING_DIMENSIONS = 1024;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final VectorRagProperties vectorRagProperties;
    private final EmbeddingModel embeddingModel;

    public List<Bm25RetrieverService.RetrievalHit> search(String query, int topK, double threshold) {
        if (!vectorRagProperties.isEnabled() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }

        String queryEmbedding = toEmbeddingVector(embeddingModel.embed(query));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("embedding", queryEmbedding)
                .addValue("threshold", threshold)
                .addValue("embeddingModel", vectorRagProperties.getEmbeddingModel())
                .addValue("embeddingVersion", vectorRagProperties.getEmbeddingVersion())
                .addValue("topK", topK);

        return jdbcTemplate.query(
                """
                SELECT
                  dc.chunk_id AS chunk_id,
                  dc.doc_path AS doc_path,
                  dc.heading_path AS heading_path,
                  dc.content AS content,
                  dc.source_type AS source_type,
                  dc.source_id AS source_id,
                  (1 - (dce.embedding <=> CAST(:embedding AS vector))) AS score
                FROM doc_chunk_embedding dce
                INNER JOIN doc_chunk dc ON dc.chunk_id = dce.chunk_id
                WHERE dc.is_deleted = FALSE
                  AND dce.embedding_model = :embeddingModel
                  AND dce.embedding_version = :embeddingVersion
                  AND (1 - (dce.embedding <=> CAST(:embedding AS vector))) >= :threshold
                ORDER BY dce.embedding <=> CAST(:embedding AS vector), dc.chunk_id
                LIMIT :topK
                """,
                params,
                (rs, rowNum) -> {
                    String content = rs.getString("content");
                    DocsChunk chunk = new DocsChunk(
                            rs.getString("chunk_id"),
                            rs.getString("doc_path"),
                            rs.getString("heading_path"),
                            content,
                            estimateTokens(content),
                            rs.getString("source_type"),
                            rs.getString("source_id"));
                    return new Bm25RetrieverService.RetrievalHit(chunk, rs.getDouble("score"));
                });
    }

    private int estimateTokens(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, content.length() / 4);
    }

    private String toEmbeddingVector(float[] embedding) {
        if (embedding == null || embedding.length != EMBEDDING_DIMENSIONS) {
            throw new IllegalStateException("Unexpected query embedding dimension: "
                    + (embedding == null ? 0 : embedding.length)
                    + ", expected "
                    + EMBEDDING_DIMENSIONS);
        }

        List<String> values = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            values.add(String.format(Locale.ROOT, "%.6f", value));
        }
        return "[" + String.join(",", values) + "]";
    }
}
