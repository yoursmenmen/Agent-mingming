package com.mingming.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class VectorRetrieverServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Mock
    private EmbeddingModel embeddingModel;

    @SuppressWarnings("unchecked")
    @Test
    void search_shouldFilterByConfiguredEmbeddingModelAndVersion() {
        VectorRagProperties properties = new VectorRagProperties();
        properties.setEnabled(true);
        properties.setEmbeddingModel("model-x");
        properties.setEmbeddingVersion("v-test");
        VectorRetrieverService service = new VectorRetrieverService(jdbcTemplate, properties, embeddingModel);

        when(embeddingModel.embed("what is vector")).thenReturn(unitEmbedding());

        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        service.search("what is vector", 3, 0.2D);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));

        assertThat(sqlCaptor.getValue()).contains("dce.embedding_model = :embeddingModel");
        assertThat(sqlCaptor.getValue()).contains("dce.embedding_version = :embeddingVersion");
        assertThat(paramsCaptor.getValue().getValue("embeddingModel")).isEqualTo("model-x");
        assertThat(paramsCaptor.getValue().getValue("embeddingVersion")).isEqualTo("v-test");
        assertThat(((String) paramsCaptor.getValue().getValue("embedding")).split(",")).hasSize(1024);
    }

    private float[] unitEmbedding() {
        float[] values = new float[1024];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i % 10) / 10.0f;
        }
        return values;
    }
}
