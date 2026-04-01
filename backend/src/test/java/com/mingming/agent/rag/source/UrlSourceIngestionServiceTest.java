package com.mingming.agent.rag.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mingming.agent.entity.RagSourceSyncStateEntity;
import com.mingming.agent.rag.DocsChunk;
import com.mingming.agent.rag.DocsChunkingService;
import com.mingming.agent.repository.RagSourceSyncStateRepository;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;

class UrlSourceIngestionServiceTest {

    @Test
    void loadChunks_shouldReturnNormalizedUrlChunks() {
        UrlSourceProperties properties = new UrlSourceProperties();
        properties.setEnabled(true);
        UrlSourceProperties.Item item = new UrlSourceProperties.Item();
        item.setName("official-docs");
        item.setUrl("https://example.com/docs");
        item.setEnabled(true);
        properties.setItems(List.of(item));
        String officialSourceId = UrlSourceIdUtil.toSourceId("official-docs", "https://example.com/docs");

        DocsChunkingService chunkingService = mock(DocsChunkingService.class);
        RagSourceSyncStateRepository sourceStateRepository = mock(RagSourceSyncStateRepository.class);
        when(sourceStateRepository.findById(officialSourceId)).thenReturn(Optional.empty());
        when(sourceStateRepository.save(org.mockito.ArgumentMatchers.any(RagSourceSyncStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chunkingService.chunkMarkdown(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(new DocsChunk("c1", "url/official-docs.md", "A", "hello", 2)));

        UrlSourceIngestionService service =
                org.mockito.Mockito.spy(new UrlSourceIngestionService(properties, chunkingService, sourceStateRepository));
        doReturn(new UrlSourceIngestionService.FetchResult(false, "<html><body><h1>Title</h1><p>Hello world</p></body></html>", null, null))
                .when(service)
                .fetchSource(org.mockito.ArgumentMatchers.eq("https://example.com/docs"), org.mockito.ArgumentMatchers.any(RagSourceSyncStateEntity.class));

        List<DocsChunk> chunks = service.loadChunks();

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sourceType()).isEqualTo("url");
        assertThat(chunks.get(0).sourceId()).isEqualTo(officialSourceId);
    }

    @Test
    void loadChunks_shouldSkipBrokenSourceAndContinue() {
        UrlSourceProperties properties = new UrlSourceProperties();
        properties.setEnabled(true);

        UrlSourceProperties.Item broken = new UrlSourceProperties.Item();
        broken.setName("broken");
        broken.setUrl("https://example.com/broken");

        UrlSourceProperties.Item ok = new UrlSourceProperties.Item();
        ok.setName("ok");
        ok.setUrl("https://example.com/ok");
        properties.setItems(List.of(broken, ok));
        String brokenSourceId = UrlSourceIdUtil.toSourceId("broken", "https://example.com/broken");
        String okSourceId = UrlSourceIdUtil.toSourceId("ok", "https://example.com/ok");

        DocsChunkingService chunkingService = mock(DocsChunkingService.class);
        RagSourceSyncStateRepository sourceStateRepository = mock(RagSourceSyncStateRepository.class);
        when(sourceStateRepository.findById(brokenSourceId)).thenReturn(Optional.empty());
        when(sourceStateRepository.findById(okSourceId)).thenReturn(Optional.empty());
        when(sourceStateRepository.save(org.mockito.ArgumentMatchers.any(RagSourceSyncStateEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chunkingService.chunkMarkdown(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(new DocsChunk("ok-1", "url/ok.md", "A", "ok", 1)));

        UrlSourceIngestionService service =
                org.mockito.Mockito.spy(new UrlSourceIngestionService(properties, chunkingService, sourceStateRepository));
        doThrow(new IllegalStateException("boom"))
                .when(service)
                .fetchSource(org.mockito.ArgumentMatchers.eq("https://example.com/broken"), org.mockito.ArgumentMatchers.any(RagSourceSyncStateEntity.class));
        doReturn(new UrlSourceIngestionService.FetchResult(false, "plain text", null, null))
                .when(service)
                .fetchSource(org.mockito.ArgumentMatchers.eq("https://example.com/ok"), org.mockito.ArgumentMatchers.any(RagSourceSyncStateEntity.class));

        List<DocsChunk> chunks = service.loadChunks();

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sourceId()).isEqualTo(okSourceId);
    }
}
