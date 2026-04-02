package com.mingming.agent.rag.source;

import com.mingming.agent.entity.DocChunkEntity;
import com.mingming.agent.entity.RagSourceSyncStateEntity;
import com.mingming.agent.rag.DocsChunk;
import com.mingming.agent.rag.DocsChunkingService;
import com.mingming.agent.repository.DocChunkRepository;
import com.mingming.agent.repository.RagSourceSyncStateRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class UrlSourceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(UrlSourceIngestionService.class);

    private final UrlSourceProperties urlSourceProperties;
    private final DocsChunkingService docsChunkingService;
    private final DocChunkRepository docChunkRepository;
    private final RagSourceSyncStateRepository sourceSyncStateRepository;
    private final WebClient webClient;

    public UrlSourceIngestionService(
            UrlSourceProperties urlSourceProperties,
            DocsChunkingService docsChunkingService,
            DocChunkRepository docChunkRepository,
            RagSourceSyncStateRepository sourceSyncStateRepository) {
        this.urlSourceProperties = urlSourceProperties;
        this.docsChunkingService = docsChunkingService;
        this.docChunkRepository = docChunkRepository;
        this.sourceSyncStateRepository = sourceSyncStateRepository;
        int maxBytes = Math.max(urlSourceProperties.getMaxInMemorySizeBytes(), 256 * 1024);
        this.webClient = WebClient.builder()
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxBytes))
                        .build())
                .build();
    }

    public List<DocsChunk> loadChunks() {
        if (!urlSourceProperties.isEnabled()) {
            return List.of();
        }

        List<DocsChunk> all = new ArrayList<>();
        for (UrlSourceProperties.Item item : urlSourceProperties.getItems()) {
            if (item == null || !item.isEnabled()) {
                continue;
            }
            String name = safeName(item.getName());
            String url = item.getUrl();
            if (url == null || url.isBlank()) {
                continue;
            }
            String sourceId = UrlSourceIdUtil.toSourceId(name, url);
            String docPath = Path.of("url", name + ".md").toString().replace('\\', '/');
            List<DocsChunk> persistedChunks = loadPersistedChunks(sourceId, docPath);
            RagSourceSyncStateEntity state = sourceSyncStateRepository.findById(sourceId).orElseGet(() -> initState(sourceId));

            try {
                FetchResult fetch = fetchSource(url, state, persistedChunks.isEmpty());
                if (fetch.notModified()) {
                    markSkipped(state, "NOT_MODIFIED", null, fetch.etag(), fetch.lastModified());
                    all.addAll(persistedChunks);
                    log.info(
                            "RAG url source unchanged via conditional request: name={}, sourceId={}, url={}, reusedChunks={}",
                            name,
                            sourceId,
                            url,
                            persistedChunks.size());
                    continue;
                }

                String normalized = normalizeText(fetch.body());
                String docHash = sha256Hex(normalized);
                if (docHash.equals(state.getLastDocHash())) {
                    markSkipped(state, "DOC_HASH_UNCHANGED", null, fetch.etag(), fetch.lastModified());
                    all.addAll(persistedChunks);
                    log.info(
                            "RAG url source unchanged via doc hash: name={}, sourceId={}, url={}, reusedChunks={}",
                            name,
                            sourceId,
                            url,
                            persistedChunks.size());
                    continue;
                }

                List<DocsChunk> chunks = docsChunkingService.chunkMarkdown(Path.of("url", name + ".md"), normalized).stream()
                        .map(chunk -> new DocsChunk(
                                chunk.chunkId(),
                                chunk.docPath(),
                                chunk.headingPath(),
                                chunk.content(),
                                chunk.tokenEstimate(),
                                "url",
                                sourceId))
                        .toList();
                all.addAll(chunks);

                OffsetDateTime now = OffsetDateTime.now();
                state.setEtag(fetch.etag());
                state.setLastModified(fetch.lastModified());
                state.setLastDocHash(docHash);
                state.setLastCheckedAt(now);
                state.setLastSuccessAt(now);
                state.setLastStatus("SUCCESS");
                state.setLastError(null);
                sourceSyncStateRepository.save(state);
                log.info(
                        "RAG url source synced: name={}, sourceId={}, url={}, chunks={}, normalizedChars={}",
                        name,
                        sourceId,
                        url,
                        chunks.size(),
                        normalized.length());
            } catch (RuntimeException ex) {
                all.addAll(persistedChunks);
                state.setLastCheckedAt(OffsetDateTime.now());
                state.setLastStatus("FAILED");
                state.setLastError(truncateError(ex.getMessage()));
                sourceSyncStateRepository.save(state);

                log.warn(
                        "Skip url source because fetch failed: name={}, sourceId={}, url={}, reusedChunks={}, exceptionType={}, message={}",
                        name,
                        sourceId,
                        url,
                        persistedChunks.size(),
                        ex.getClass().getSimpleName(),
                        ex.getMessage());
            }
        }
        log.info("RAG url ingestion completed: configuredSources={}, materializedChunks={}", urlSourceProperties.getItems().size(), all.size());
        return all;
    }

    private List<DocsChunk> loadPersistedChunks(String sourceId, String docPath) {
        return docChunkRepository.findBySourceIdAndDocPath(sourceId, docPath).stream()
                .filter(entity -> !entity.isDeleted())
                .map(this::toDocsChunk)
                .toList();
    }

    private DocsChunk toDocsChunk(DocChunkEntity entity) {
        String content = entity.getContent() == null ? "" : entity.getContent();
        return new DocsChunk(
                entity.getChunkId(),
                entity.getDocPath(),
                entity.getHeadingPath(),
                content,
                Math.max(1, content.length() / 2),
                entity.getSourceType(),
                entity.getSourceId());
    }

    FetchResult fetchSource(String url, RagSourceSyncStateEntity state, boolean skipConditionalHeaders) {
        return webClient.get()
                .uri(url)
                .headers(headers -> applyConditionalHeaders(headers, state, skipConditionalHeaders))
                .exchangeToMono(response -> {
                    String etag = response.headers().asHttpHeaders().getETag();
                    String lastModified = response.headers().asHttpHeaders().getFirst(HttpHeaders.LAST_MODIFIED);
                    if (response.statusCode().value() == 304) {
                        return Mono.just(new FetchResult(
                                true,
                                "",
                                valueOrFallback(etag, state.getEtag()),
                                valueOrFallback(lastModified, state.getLastModified())));
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.createException().flatMap(Mono::error);
                    }
                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> new FetchResult(
                                    false,
                                    body,
                                    valueOrFallback(etag, state.getEtag()),
                                    valueOrFallback(lastModified, state.getLastModified())));
                })
                .block(Duration.ofSeconds(20));
    }

    String normalizeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw;
        if (looksLikeHtml(text)) {
            text = text.replaceAll("(?is)<script.*?>.*?</script>", " ")
                    .replaceAll("(?is)<style.*?>.*?</style>", " ")
                    .replaceAll("(?is)<[^>]+>", " ");
        }
        return text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private void applyConditionalHeaders(HttpHeaders headers, RagSourceSyncStateEntity state, boolean skipConditionalHeaders) {
        if (skipConditionalHeaders) {
            return;
        }
        if (state.getEtag() != null && !state.getEtag().isBlank()) {
            headers.setIfNoneMatch(state.getEtag());
        } else if (state.getLastModified() != null && !state.getLastModified().isBlank()) {
            headers.set(HttpHeaders.IF_MODIFIED_SINCE, state.getLastModified());
        }
    }

    private RagSourceSyncStateEntity initState(String sourceId) {
        RagSourceSyncStateEntity state = new RagSourceSyncStateEntity();
        state.setSourceId(sourceId);
        state.setLastStatus("UNKNOWN");
        state.setLastCheckedAt(OffsetDateTime.now());
        return state;
    }

    private void markSkipped(
            RagSourceSyncStateEntity state, String status, String error, String etag, String lastModified) {
        state.setEtag(etag);
        state.setLastModified(lastModified);
        state.setLastCheckedAt(OffsetDateTime.now());
        state.setLastStatus(status);
        state.setLastError(error == null ? null : truncateError(error));
        sourceSyncStateRepository.save(state);
    }

    private String valueOrFallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private boolean looksLikeHtml(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("<html") || lower.contains("<body") || lower.matches("(?s).*</[a-z][a-z0-9]*>.*");
    }

    private String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "unnamed";
        }
        return value.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    private String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    record FetchResult(boolean notModified, String body, String etag, String lastModified) {}
}
