package com.mingming.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.mcp.McpRuntimeToolCallbackFactory;
import com.mingming.agent.rag.Bm25RetrieverService;
import com.mingming.agent.rag.DocsChunk;
import com.mingming.agent.rag.DocsChunkingService;
import com.mingming.agent.rag.HybridRetrievalService;
import com.mingming.agent.rag.RetrievalEventService;
import com.mingming.agent.rag.VectorRagProperties;
import com.mingming.agent.repository.AgentRunRepository;
import com.mingming.agent.repository.ChatSessionRepository;
import com.mingming.agent.repository.RunEventRepository;
import com.mingming.agent.tool.LocalToolProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock
    private ObjectProvider<ChatModel> chatModelProvider;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private RunEventRepository runEventRepository;

    @Mock
    private DocsChunkingService docsChunkingService;

    @Mock
    private HybridRetrievalService hybridRetrievalService;

    @Mock
    private RetrievalEventService retrievalEventService;

    @Mock
    private McpRuntimeToolCallbackFactory mcpRuntimeToolCallbackFactory;

    private final VectorRagProperties vectorRagProperties = new VectorRagProperties();

    AgentOrchestratorTest() {
        vectorRagProperties.setDocsRoot("../docs");
    }

    @Test
    void runOnce_shouldPersistUserAndModelEventsWithIncreasingSeq() throws Exception {
        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        when(docsChunkingService.loadChunks(any())).thenReturn(List.of());
        when(hybridRetrievalService.searchWithObservability(eq("你好，测试消息"), eq(List.<DocsChunk>of()), eq(3), eq(0.0D), eq(3), eq(0.0D), eq(3)))
                .thenReturn(new HybridRetrievalService.RetrievalResult("hybrid", 0, 0, 0, List.of()));

        AgentOrchestrator orchestrator = createOrchestrator();

        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(runEventRepository.findRecentConversationEvents(sessionId, 40)).thenReturn(List.of());
        when(runEventRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of());

        List<String> ssePayloads = new ArrayList<>();

        orchestrator.runOnce(runId, sessionId, "你好，测试消息", ssePayloads::add);

        ArgumentCaptor<RunEventEntity> captor = ArgumentCaptor.forClass(RunEventEntity.class);
        verify(runEventRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        List<RunEventEntity> savedEvents = captor.getAllValues();
        assertThat(savedEvents).hasSize(2);

        RunEventEntity first = savedEvents.get(0);
        assertThat(first.getRunId()).isEqualTo(runId);
        assertThat(first.getSeq()).isEqualTo(1);
        assertThat(first.getType()).isEqualTo("USER_MESSAGE");
        assertThat(new ObjectMapper().readTree(first.getPayload()).path("content").asText())
                .isEqualTo("你好，测试消息");

        RunEventEntity second = savedEvents.get(1);
        assertThat(second.getRunId()).isEqualTo(runId);
        assertThat(second.getSeq()).isEqualTo(3);
        assertThat(second.getType()).isEqualTo("MODEL_MESSAGE");

        assertThat(ssePayloads).hasSize(1);
        assertThat(new ObjectMapper().readTree(ssePayloads.get(0)).has("content")).isTrue();

        ArgumentCaptor<RetrievalEventService.RetrievalMeta> retrievalMetaCaptor =
                ArgumentCaptor.forClass(RetrievalEventService.RetrievalMeta.class);
        ArgumentCaptor<List<RetrievalEventService.RetrievalResultHit>> retrievalHitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrievalEventService)
                .record(eq(runId), eq(2), eq("你好，测试消息"), retrievalMetaCaptor.capture(), retrievalHitsCaptor.capture());
        assertThat(retrievalMetaCaptor.getValue().strategy()).isEqualTo("hybrid");
        assertThat(retrievalMetaCaptor.getValue().vectorHitCount()).isEqualTo(0);
        assertThat(retrievalMetaCaptor.getValue().bm25HitCount()).isEqualTo(0);
        assertThat(retrievalMetaCaptor.getValue().finalHitCount()).isEqualTo(0);
        assertThat(retrievalHitsCaptor.getValue()).isEmpty();
        verify(hybridRetrievalService)
                .searchWithObservability(eq("你好，测试消息"), eq(List.<DocsChunk>of()), eq(3), eq(0.0D), eq(3), eq(0.0D), eq(3));
    }

    @Test
    void runOnce_shouldUseHybridRetrievalWhenDocsAvailable() {
        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        when(docsChunkingService.loadChunks(any()))
                .thenReturn(List.of(new DocsChunk("chunk-a", "docs/a.md", "A", "检索片段", 8)));
        when(hybridRetrievalService.searchWithObservability(eq("请检索"), any(), eq(3), eq(0.0D), eq(3), eq(0.0D), eq(3)))
                .thenReturn(new HybridRetrievalService.RetrievalResult(
                        "hybrid",
                        2,
                        1,
                        1,
                        List.of(new HybridRetrievalService.RetrievalResultHit(
                                new Bm25RetrieverService.RetrievalHit(
                                        new DocsChunk("chunk-a", "docs/a.md", "A", "检索片段", 8),
                                        1.0),
                                "bm25"))));

        AgentOrchestrator orchestrator = createOrchestrator();

        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(runEventRepository.findRecentConversationEvents(sessionId, 40)).thenReturn(List.of());
        when(runEventRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of());

        orchestrator.runOnce(runId, sessionId, "请检索", payload -> {});

        verify(hybridRetrievalService).searchWithObservability(eq("请检索"), any(), eq(3), eq(0.0D), eq(3), eq(0.0D), eq(3));
    }

    @Test
    void buildPromptMessages_shouldInjectRetrievalContextBeforeCurrentUserMessage() {
        AgentOrchestrator orchestrator = createOrchestrator();

        UUID sessionId = UUID.randomUUID();
        when(runEventRepository.findRecentConversationEvents(sessionId, 40)).thenReturn(List.of());

        List<Bm25RetrieverService.RetrievalHit> retrievalHits = List.of(new Bm25RetrieverService.RetrievalHit(
                new DocsChunk("chunk-1", "docs/project-overview.md", "项目概览", "这是召回内容", 8),
                1.23));

        List<Message> promptMessages = orchestrator.buildPromptMessages(sessionId, "当前问题", retrievalHits);

        assertThat(promptMessages).hasSize(2);
        assertThat(promptMessages.get(0).getText()).contains("docs/project-overview.md");
        assertThat(promptMessages.get(0).getText()).contains("这是召回内容");
        assertThat(promptMessages.get(1).getText()).isEqualTo("当前问题");
    }

    @Test
    void runOnce_shouldGracefullyDegradeWhenDocsUnavailable() {
        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        when(docsChunkingService.loadChunks(any())).thenThrow(new IllegalStateException("docs missing"));
        when(hybridRetrievalService.searchWithObservability(eq("文档不可用时也要继续"), eq(List.<DocsChunk>of()), eq(3), eq(0.0D), eq(3), eq(0.0D), eq(3)))
                .thenReturn(new HybridRetrievalService.RetrievalResult("hybrid", 0, 0, 0, List.of()));

        AgentOrchestrator orchestrator = createOrchestrator();

        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(runEventRepository.findRecentConversationEvents(sessionId, 40)).thenReturn(List.of());
        when(runEventRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of());

        List<String> ssePayloads = new ArrayList<>();
        orchestrator.runOnce(runId, sessionId, "文档不可用时也要继续", ssePayloads::add);

        ArgumentCaptor<RetrievalEventService.RetrievalMeta> retrievalMetaCaptor =
                ArgumentCaptor.forClass(RetrievalEventService.RetrievalMeta.class);
        ArgumentCaptor<List<RetrievalEventService.RetrievalResultHit>> retrievalHitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrievalEventService)
                .record(eq(runId), eq(2), eq("文档不可用时也要继续"), retrievalMetaCaptor.capture(), retrievalHitsCaptor.capture());
        assertThat(retrievalMetaCaptor.getValue().finalHitCount()).isEqualTo(0);
        assertThat(retrievalHitsCaptor.getValue()).isEmpty();
        verify(hybridRetrievalService)
                .searchWithObservability(eq("文档不可用时也要继续"), eq(List.<DocsChunk>of()), eq(3), eq(0.0D), eq(3), eq(0.0D), eq(3));
        assertThat(ssePayloads).hasSize(1);
    }

    @Test
    void runOnce_shouldLoadDocsFromConfiguredVectorDocsRoot() {
        vectorRagProperties.setDocsRoot("custom-docs");
        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        when(docsChunkingService.loadChunks(Path.of("custom-docs")))
                .thenReturn(List.of(new DocsChunk("chunk-root", "docs/root.md", "Root", "root", 2)));
        when(hybridRetrievalService.searchWithObservability(eq("读取配置路径"), any(), eq(3), eq(0.0D), eq(3), eq(0.0D), eq(3)))
                .thenReturn(new HybridRetrievalService.RetrievalResult("hybrid", 1, 1, 1, List.of()));

        AgentOrchestrator orchestrator = createOrchestrator();
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(runEventRepository.findRecentConversationEvents(sessionId, 40)).thenReturn(List.of());
        when(runEventRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of());

        orchestrator.runOnce(runId, sessionId, "读取配置路径", payload -> {});

        verify(docsChunkingService).loadChunks(Path.of("custom-docs"));
    }

    @Test
    void runOnce_shouldFallbackToDefaultDocsRootsWhenPrimaryDocsRootReturnsEmpty() {
        vectorRagProperties.setDocsRoot("missing-docs");
        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        when(docsChunkingService.loadChunks(Path.of("missing-docs"))).thenReturn(List.of());
        List<DocsChunk> fallbackChunks = List.of(new DocsChunk("chunk-fallback", "docs/fallback.md", "Fallback", "fallback", 3));
        when(docsChunkingService.loadChunks(Path.of("../docs"))).thenReturn(fallbackChunks);
        when(hybridRetrievalService.searchWithObservability(eq("触发回退路径"), eq(fallbackChunks), eq(3), eq(0.0D), eq(3), eq(0.0D), eq(3)))
                .thenReturn(new HybridRetrievalService.RetrievalResult("hybrid", 1, 1, 1, List.of()));

        AgentOrchestrator orchestrator = createOrchestrator();
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(runEventRepository.findRecentConversationEvents(sessionId, 40)).thenReturn(List.of());
        when(runEventRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of());

        orchestrator.runOnce(runId, sessionId, "触发回退路径", payload -> {});

        verify(docsChunkingService).loadChunks(Path.of("missing-docs"));
        verify(docsChunkingService).loadChunks(Path.of("../docs"));
        verify(hybridRetrievalService)
                .searchWithObservability(eq("触发回退路径"), eq(fallbackChunks), eq(3), eq(0.0D), eq(3), eq(0.0D), eq(3));
    }

    @Test
    void runOnce_shouldRecordRetrievalEventWithRealObservabilityMetadataAndHitSources() {
        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        when(docsChunkingService.loadChunks(any()))
                .thenReturn(List.of(new DocsChunk("chunk-a", "docs/a.md", "A", "检索片段", 8)));

        HybridRetrievalService.RetrievalResult retrievalResult = new HybridRetrievalService.RetrievalResult(
                "hybrid",
                2,
                3,
                2,
                List.of(
                        new HybridRetrievalService.RetrievalResultHit(
                                new Bm25RetrieverService.RetrievalHit(
                                        new DocsChunk("chunk-a", "docs/a.md", "A", "检索片段", 8),
                                        1.2),
                                "hybrid"),
                        new HybridRetrievalService.RetrievalResultHit(
                                new Bm25RetrieverService.RetrievalHit(
                                        new DocsChunk("chunk-b", "docs/b.md", "B", "向量片段", 7),
                                        1.1),
                                "vector")));
        when(hybridRetrievalService.searchWithObservability(eq("观测字段"), any(), eq(3), eq(0.0D), eq(3), eq(0.0D), eq(3)))
                .thenReturn(retrievalResult);

        AgentOrchestrator orchestrator = createOrchestrator();
        UUID runId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(runEventRepository.findRecentConversationEvents(sessionId, 40)).thenReturn(List.of());
        when(runEventRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of());

        orchestrator.runOnce(runId, sessionId, "观测字段", payload -> {});

        ArgumentCaptor<RetrievalEventService.RetrievalMeta> retrievalMetaCaptor =
                ArgumentCaptor.forClass(RetrievalEventService.RetrievalMeta.class);
        ArgumentCaptor<List<RetrievalEventService.RetrievalResultHit>> retrievalHitsCaptor = ArgumentCaptor.forClass(List.class);
        verify(retrievalEventService)
                .record(eq(runId), eq(2), eq("观测字段"), retrievalMetaCaptor.capture(), retrievalHitsCaptor.capture());
        assertThat(retrievalMetaCaptor.getValue().strategy()).isEqualTo("hybrid");
        assertThat(retrievalMetaCaptor.getValue().vectorHitCount()).isEqualTo(2);
        assertThat(retrievalMetaCaptor.getValue().bm25HitCount()).isEqualTo(3);
        assertThat(retrievalMetaCaptor.getValue().finalHitCount()).isEqualTo(2);
        assertThat(retrievalHitsCaptor.getValue()).extracting(RetrievalEventService.RetrievalResultHit::source)
                .containsExactly("hybrid", "vector");
    }

    @Test
    void buildPromptMessages_shouldIncludeHistoryAndCurrentUserMessage() {
        AgentOrchestrator orchestrator = createOrchestrator();

        UUID previousRunId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        RunEventEntity historyUser = new RunEventEntity();
        historyUser.setRunId(previousRunId);
        historyUser.setType("USER_MESSAGE");
        historyUser.setPayload("{\"content\":\"历史问题\"}");

        RunEventEntity historyModel = new RunEventEntity();
        historyModel.setRunId(previousRunId);
        historyModel.setType("MODEL_MESSAGE");
        historyModel.setPayload("{\"content\":\"历史回答\"}");

        when(runEventRepository.findRecentConversationEvents(sessionId, 40))
                .thenReturn(List.of(historyModel, historyUser));

        List<Message> promptMessages = orchestrator.buildPromptMessages(sessionId, "当前问题");

        assertThat(promptMessages).hasSize(3);
        assertThat(promptMessages.get(0).getText()).isEqualTo("历史问题");
        assertThat(promptMessages.get(1).getText()).isEqualTo("历史回答");
        assertThat(promptMessages.get(2).getText()).isEqualTo("当前问题");
    }

    @Test
    void buildPromptMessages_shouldLimitHistorySizeForContextWindow() {
        AgentOrchestrator orchestrator = createOrchestrator();

        UUID sessionId = UUID.randomUUID();

        List<RunEventEntity> historyEvents = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            RunEventEntity event = new RunEventEntity();
            event.setRunId(UUID.randomUUID());
            event.setType("USER_MESSAGE");
            event.setPayload("{\"content\":\"历史消息" + i + "\"}");
            historyEvents.add(event);
        }
        when(runEventRepository.findRecentConversationEvents(sessionId, 40)).thenReturn(historyEvents);

        List<Message> promptMessages = orchestrator.buildPromptMessages(sessionId, "当前问题");

        assertThat(promptMessages).hasSize(20);
        assertThat(promptMessages.get(19).getText()).isEqualTo("当前问题");
    }

    @Test
    void startRun_shouldReuseExistingSessionId() {
        when(chatSessionRepository.existsById(any(UUID.class))).thenReturn(true);

        AgentOrchestrator orchestrator = createOrchestrator();

        UUID existingSessionId = UUID.randomUUID();
        AgentOrchestrator.RunInit runInit = orchestrator.startRun(existingSessionId, "dashscope", null, null, "system.txt");

        assertThat(runInit.sessionId()).isEqualTo(existingSessionId);
        verify(chatSessionRepository, never()).save(any());
        verify(agentRunRepository).save(any());
    }

    @Test
    void startRun_shouldThrowWhenSessionIdNotFound() {
        when(chatSessionRepository.existsById(any(UUID.class))).thenReturn(false);

        AgentOrchestrator orchestrator = createOrchestrator();

        UUID missingSessionId = UUID.randomUUID();

        assertThatThrownBy(() -> orchestrator.startRun(missingSessionId, "dashscope", null, null, "system.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId not found");
        verify(chatSessionRepository, never()).save(any());
        verify(agentRunRepository, never()).save(any());
    }

    @Test
    void buildFinalModelMessagePayload_shouldContainStructuredPayloadFromAssembler() {
        AgentOrchestrator orchestrator = createOrchestrator();

        UUID runId = UUID.randomUUID();
        RunEventEntity weatherToolResult = new RunEventEntity();
        weatherToolResult.setType("TOOL_RESULT");
        weatherToolResult.setPayload("{\"tool\":\"get_weather\",\"source\":\"amap\",\"data\":{\"ok\":true,\"city\":\"北京\",\"condition\":\"晴\",\"tempC\":26.0,\"feelsLikeC\":27.0,\"humidity\":42,\"windKph\":13.5}}");
        when(runEventRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of(weatherToolResult));

        ObjectNode payload = orchestrator.buildFinalModelMessagePayload(runId, "北京现在晴，26度");

        assertThat(payload.path("content").asText()).isEqualTo("北京现在晴，26度");
        assertThat(payload.path("structured").path("type").asText()).isEqualTo("weather");
        assertThat(payload.path("structured").path("version").asText()).isEqualTo("v1");
        assertThat(payload.path("structured").path("data").path("city").asText()).isEqualTo("北京");
        assertThat(payload.path("structured").path("data").path("condition").asText()).isEqualTo("晴");
        assertThat(payload.path("structured").path("meta").path("toolName").asText()).isEqualTo("get_weather");
    }

    private AgentOrchestrator createOrchestrator() {
        return new AgentOrchestrator(
                chatModelProvider,
                new ObjectMapper(),
                chatSessionRepository,
                agentRunRepository,
                runEventRepository,
                List.<LocalToolProvider>of(),
                new StructuredPayloadAssembler(new ObjectMapper()),
                docsChunkingService,
                vectorRagProperties,
                hybridRetrievalService,
                retrievalEventService,
                mcpRuntimeToolCallbackFactory);
    }
}
