package com.mingming.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class Bm25RetrieverServiceTest {

    private final Bm25RetrieverService service = new Bm25RetrieverService();

    @Test
    void search_shouldReturnScoredHitsSortedByScoreDesc() {
        DocsChunk low = chunk("c-low", "检索能力用于文档问答");
        DocsChunk high = chunk("c-high", "检索 检索 检索 可以提升召回");
        DocsChunk none = chunk("c-none", "天气晴朗适合散步");

        List<Bm25RetrieverService.RetrievalHit> hits = service.search("检索", List.of(low, high, none), 5, 0.0);

        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(hit -> hit.chunk().chunkId()).containsExactly("c-high", "c-low");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    void search_shouldUseChineseBigramAndEnglishNumberTokenization() {
        DocsChunk both = chunk("c-both", "Spring 3 检索链路能力");
        DocsChunk chineseOnly = chunk("c-cn", "检索链路功能");
        DocsChunk englishNumberOnly = chunk("c-en-num", "Spring 3 reference");
        DocsChunk unrelated = chunk("c-none", "天气晴朗适合散步");

        List<Bm25RetrieverService.RetrievalHit> hits =
                service.search("Spring 3 检索链路", List.of(unrelated, chineseOnly, englishNumberOnly, both), 5, 0.0);

        assertThat(hits).extracting(hit -> hit.chunk().chunkId()).containsExactly("c-both", "c-cn", "c-en-num");
    }

    @Test
    void search_shouldApplyTopKAndThreshold() {
        DocsChunk best = chunk("c-best", "retrieval retrieval retrieval");
        DocsChunk second = chunk("c-second", "retrieval pipeline");

        List<Bm25RetrieverService.RetrievalHit> topOne = service.search("retrieval", List.of(best, second), 1, 0.0);
        List<Bm25RetrieverService.RetrievalHit> filtered = service.search("retrieval", List.of(best, second), 5, 10.0);

        assertThat(topOne).extracting(hit -> hit.chunk().chunkId()).containsExactly("c-best");
        assertThat(filtered).isEmpty();
    }

    @Test
    void search_shouldReturnEmptyWhenNoHitOrInputInvalid() {
        DocsChunk chunk = chunk("c-1", "anything");

        assertThat(service.search("", List.of(chunk), 5, 0.0)).isEmpty();
        assertThat(service.search("query", List.of(), 5, 0.0)).isEmpty();
        assertThat(service.search("query", List.of(chunk), 0, 0.0)).isEmpty();
        assertThat(service.search("unmatched tokens", List.of(chunk), 5, 0.0)).isEmpty();
    }

    private DocsChunk chunk(String chunkId, String content) {
        return new DocsChunk(chunkId, "docs/test.md", "测试", content, Math.max(1, content.length() / 2));
    }
}
