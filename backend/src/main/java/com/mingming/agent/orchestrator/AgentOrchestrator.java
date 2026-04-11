package com.mingming.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.mingming.agent.entity.AgentRunEntity;
import com.mingming.agent.entity.ChatSessionEntity;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.event.contract.EventContractRegistry;
import com.mingming.agent.mcp.McpRuntimeToolCallbackFactory;
import com.mingming.agent.rag.DocsChunk;
import com.mingming.agent.rag.DocsChunkingService;
import com.mingming.agent.rag.HybridRetrievalService;
import com.mingming.agent.rag.RetrievalEventService;
import com.mingming.agent.rag.Bm25RetrieverService;
import com.mingming.agent.rag.VectorRagProperties;
import com.mingming.agent.repository.AgentRunRepository;
import com.mingming.agent.repository.ChatSessionRepository;
import com.mingming.agent.repository.RunEventRepository;
import com.mingming.agent.tool.LocalToolProvider;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final int MAX_CONTEXT_MESSAGES = 20;
    private static final int MAX_CONTEXT_CHARS = 12_000;
    private static final int RETRIEVAL_BM25_TOP_K = 3;
    private static final double RETRIEVAL_BM25_THRESHOLD = 0.0D;
    private static final int RETRIEVAL_VECTOR_TOP_K = 3;
    private static final double RETRIEVAL_VECTOR_THRESHOLD = 0.0D;
    private static final int RETRIEVAL_FINAL_TOP_N = 3;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;
    private final ChatSessionRepository chatSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final RunEventRepository runEventRepository;
    private final List<LocalToolProvider> localToolProviders;
    private final StructuredPayloadAssembler structuredPayloadAssembler;
    private final DocsChunkingService docsChunkingService;
    private final VectorRagProperties vectorRagProperties;
    private final HybridRetrievalService hybridRetrievalService;
    private final RetrievalEventService retrievalEventService;
    private final McpRuntimeToolCallbackFactory mcpRuntimeToolCallbackFactory;

    @Autowired(required = false)
    private EventContractRegistry eventContractRegistry;

    public record RunInit(UUID sessionId, UUID runId) {}

    public RunInit startRun(UUID sessionId, String model, Double temperature, Double topP, String systemPromptVersion) {
        UUID resolvedSessionId = sessionId;
        if (resolvedSessionId == null) {
            resolvedSessionId = UUID.randomUUID();
            ChatSessionEntity session = new ChatSessionEntity();
            session.setId(resolvedSessionId);
            session.setCreatedAt(OffsetDateTime.now());
            chatSessionRepository.save(session);
        } else if (!chatSessionRepository.existsById(resolvedSessionId)) {
            throw new IllegalArgumentException("sessionId not found: " + resolvedSessionId);
        }

        UUID runId = UUID.randomUUID();

        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setSessionId(resolvedSessionId);
        run.setCreatedAt(OffsetDateTime.now());
        run.setModel(model);
        run.setTemperature(temperature);
        run.setTopP(topP);
        run.setSystemPromptVersion(systemPromptVersion);
        agentRunRepository.save(run);

        return new RunInit(resolvedSessionId, runId);
    }

    public void appendEvent(UUID runId, int seq, RunEventType type, ObjectNode payload) {
        RunEventEntity e = new RunEventEntity();
        e.setId(UUID.randomUUID());
        e.setRunId(runId);
        e.setSeq(seq);
        e.setCreatedAt(OffsetDateTime.now());
        e.setType(type.name());
        ObjectNode normalizedPayload = payload == null ? objectMapper.createObjectNode() : payload;
        if (eventContractRegistry != null) {
            normalizedPayload = eventContractRegistry.normalizeAndValidate(type, normalizedPayload);
        }
        try {
            e.setPayload(objectMapper.writeValueAsString(normalizedPayload));
        } catch (Exception ex) {
            e.setPayload("{\"error\":\"failed to serialize payload\"}");
        }
        runEventRepository.save(e);
    }

    /**
     * MVP streaming: currently emits MODEL_MESSAGE once (non-token streaming).
     * We'll evolve to true token streaming after confirming provider streaming behavior.
     */
    public void runOnce(UUID runId, UUID sessionId, String userText, java.util.function.Consumer<String> sseDataConsumer) {
        AtomicInteger seq = new AtomicInteger(1);

        ObjectNode userPayload = objectMapper.createObjectNode();
        userPayload.put("content", userText);
        appendEvent(runId, seq.getAndIncrement(), RunEventType.USER_MESSAGE, userPayload);

        List<DocsChunk> docsChunks = loadDocsChunks(runId);
        HybridRetrievalService.RetrievalResult retrievalResult = retrieve(runId, userText, docsChunks);
        List<Bm25RetrieverService.RetrievalHit> retrievalHits = retrievalResult.hits().stream()
                .map(HybridRetrievalService.RetrievalResultHit::hit)
                .toList();
        retrievalEventService.record(
                runId,
                seq.getAndIncrement(),
                userText,
                new RetrievalEventService.RetrievalMeta(
                        retrievalResult.strategy(),
                        retrievalResult.vectorHitCount(),
                        retrievalResult.bm25HitCount(),
                        retrievalResult.finalHitCount()),
                retrievalResult.hits().stream()
                        .map(hit -> new RetrievalEventService.RetrievalResultHit(hit.hit(), hit.source()))
                        .toList());

        List<Message> promptMessages = buildPromptMessages(sessionId, userText, retrievalHits);

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        String content;
        if (chatModel == null) {
            content = "当前未配置 DashScope 模型，已切换到本地回退响应。你可以先继续联调前后端链路，配置好 AI_DASHSCOPE_API_KEY 后再接入真实大模型输出。";
            ObjectNode deltaPayload = objectMapper.createObjectNode();
            deltaPayload.put("content", content);
            sseDataConsumer.accept(deltaPayload.toString());
        } else {
            content = streamModelWithTools(chatModel, runId, seq, promptMessages, sseDataConsumer);
        }

        ObjectNode payload = buildFinalModelMessagePayload(runId, content);
        appendEvent(runId, seq.getAndIncrement(), RunEventType.MODEL_MESSAGE, payload);
    }

    public List<Message> buildPromptMessages(UUID sessionId, String userText) {
        return buildPromptMessages(sessionId, userText, List.of());
    }

    public List<Message> buildSessionHistoryMessages(UUID sessionId) {
        List<Message> historyMessages = loadSessionHistoryMessages(sessionId);
        return trimHistoryMessages(historyMessages);
    }

    List<Message> buildPromptMessages(
            UUID sessionId, String userText, List<Bm25RetrieverService.RetrievalHit> retrievalHits) {
        List<Message> promptMessages = new ArrayList<>(buildSessionHistoryMessages(sessionId));
        String retrievalContext = buildRetrievalContext(retrievalHits);
        if (!retrievalContext.isBlank()) {
            promptMessages.add(new UserMessage(retrievalContext));
        }
        promptMessages.add(new UserMessage(userText));
        return promptMessages;
    }

    private List<DocsChunk> loadDocsChunks(UUID runId) {
        List<Path> candidateRoots = buildDocsRootCandidates();
        for (Path candidateRoot : candidateRoots) {
            List<DocsChunk> chunks = safeLoadChunks(runId, candidateRoot);
            if (!chunks.isEmpty()) {
                return chunks;
            }
        }
        return List.of();
    }

    private List<Path> buildDocsRootCandidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        String configuredRoot = vectorRagProperties.getDocsRoot();
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            candidates.add(Path.of(configuredRoot));
        }
        candidates.add(Path.of("../docs"));
        candidates.add(Path.of("docs"));
        return List.copyOf(candidates);
    }

    private List<DocsChunk> safeLoadChunks(UUID runId, Path docsRoot) {
        try {
            return docsChunkingService.loadChunks(docsRoot);
        } catch (RuntimeException ex) {
            log.warn(
                    "RAG retrieval degraded: failed to load docs chunks; runId={}, docsRoot={}, exceptionType={}, message={}",
                    runId,
                    docsRoot,
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex);
            return List.of();
        }
    }

    private HybridRetrievalService.RetrievalResult retrieve(UUID runId, String userText, List<DocsChunk> docsChunks) {
        try {
            return hybridRetrievalService.searchWithObservability(
                    userText,
                    docsChunks == null ? List.of() : docsChunks,
                    RETRIEVAL_BM25_TOP_K,
                    RETRIEVAL_BM25_THRESHOLD,
                    RETRIEVAL_VECTOR_TOP_K,
                    RETRIEVAL_VECTOR_THRESHOLD,
                    RETRIEVAL_FINAL_TOP_N);
        } catch (RuntimeException ex) {
            log.warn(
                    "RAG retrieval degraded: hybrid retrieval failed; runId={}, queryLength={}, queryHash={}, exceptionType={}, message={}",
                    runId,
                    userText == null ? 0 : userText.length(),
                    queryHash(userText),
                    ex.getClass().getSimpleName(),
                    ex.getMessage(),
                    ex);
            return new HybridRetrievalService.RetrievalResult("hybrid", 0, 0, 0, List.of());
        }
    }

    private String queryHash(String text) {
        if (text == null || text.isBlank()) {
            return "empty";
        }
        return Integer.toHexString(text.hashCode());
    }

    private String buildRetrievalContext(List<Bm25RetrieverService.RetrievalHit> retrievalHits) {
        if (retrievalHits == null || retrievalHits.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("检索参考资料（仅在与用户问题相关时使用）：\n");
        for (int i = 0; i < retrievalHits.size(); i++) {
            Bm25RetrieverService.RetrievalHit hit = retrievalHits.get(i);
            if (hit == null || hit.chunk() == null) {
                continue;
            }
            DocsChunk chunk = hit.chunk();
            builder.append(i + 1)
                    .append('.').append(' ')
                    .append('[')
                    .append(chunk.docPath())
                    .append(" | ")
                    .append(chunk.headingPath())
                    .append("]\n")
                    .append(chunk.content())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private List<Message> loadSessionHistoryMessages(UUID sessionId) {
        List<RunEventEntity> events = new ArrayList<>(
                runEventRepository.findRecentConversationEvents(sessionId, MAX_CONTEXT_MESSAGES * 2));
        Collections.reverse(events);

        return events.stream()
                .map(this::toPromptMessage)
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private List<Message> trimHistoryMessages(List<Message> historyMessages) {
        int historyMessageLimit = Math.max(0, MAX_CONTEXT_MESSAGES - 1);
        List<Message> reversedSelected = new ArrayList<>();
        int totalChars = 0;

        for (int i = historyMessages.size() - 1; i >= 0; i--) {
            if (reversedSelected.size() >= historyMessageLimit) {
                break;
            }
            Message candidate = historyMessages.get(i);
            int messageLength = candidate.getText() == null ? 0 : candidate.getText().length();
            if (!reversedSelected.isEmpty() && totalChars + messageLength > MAX_CONTEXT_CHARS) {
                break;
            }
            if (reversedSelected.isEmpty() && messageLength > MAX_CONTEXT_CHARS) {
                continue;
            }
            reversedSelected.add(candidate);
            totalChars += messageLength;
        }

        Collections.reverse(reversedSelected);
        return reversedSelected;
    }

    private java.util.Optional<Message> toPromptMessage(RunEventEntity event) {
        String content = extractContent(event.getPayload());
        if (content == null || content.isBlank()) {
            return java.util.Optional.empty();
        }
        if (RunEventType.USER_MESSAGE.name().equals(event.getType())) {
            return java.util.Optional.of(new UserMessage(content));
        }
        if (RunEventType.MODEL_MESSAGE.name().equals(event.getType())) {
            return java.util.Optional.of(new AssistantMessage(content));
        }
        return java.util.Optional.empty();
    }

    private String extractContent(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson).path("content").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String streamModelWithTools(
            ChatModel chatModel,
            UUID runId,
            AtomicInteger seq,
            List<Message> promptMessages,
            java.util.function.Consumer<String> sseDataConsumer) {
        StringBuilder contentBuilder = new StringBuilder();
        List<Object> localRuntimeTools = new ArrayList<>(localToolProviders.stream()
                .map(LocalToolProvider::toolBean)
                .toList());
        McpRuntimeToolCallbackFactory.RuntimeToolBundle runtimeToolBundle = mcpRuntimeToolCallbackFactory.prepareRuntimeTools();
        List<ToolCallback> mcpRuntimeTools = runtimeToolBundle.callbacks();

        ObjectNode mcpToolsBoundPayload = objectMapper.createObjectNode();
        mcpToolsBoundPayload.put("localToolCount", localRuntimeTools.size());
        mcpToolsBoundPayload.put("mcpToolCount", mcpRuntimeTools.size());
        mcpToolsBoundPayload.put("totalToolCount", localRuntimeTools.size() + mcpRuntimeTools.size());
        ArrayNode injectedTools = objectMapper.valueToTree(runtimeToolBundle.boundTools());
        mcpToolsBoundPayload.set("injectedMcpTools", injectedTools);
        ArrayNode blockedTools = objectMapper.valueToTree(runtimeToolBundle.blockedTools());
        mcpToolsBoundPayload.set("blockedMcpTools", blockedTools);
        ArrayNode discoveryErrors = objectMapper.valueToTree(runtimeToolBundle.discoveryErrors());
        mcpToolsBoundPayload.set("mcpDiscoveryErrors", discoveryErrors);
        appendEvent(runId, seq.getAndIncrement(), RunEventType.MCP_TOOLS_BOUND, mcpToolsBoundPayload);

        ChatClient.builder(chatModel)
                .build()
                .prompt()
                .messages(promptMessages.toArray(new Message[0]))
                .tools(localRuntimeTools.toArray())
                .toolCallbacks(mcpRuntimeTools.toArray(new ToolCallback[0]))
                .toolContext(Map.of(
                        "runId", runId.toString(),
                        "seqCounter", seq))
                .stream()
                .content()
                .doOnNext(delta -> {
                    if (delta == null || delta.isBlank()) {
                        return;
                    }
                    contentBuilder.append(delta);
                    ObjectNode deltaPayload = objectMapper.createObjectNode();
                    deltaPayload.put("content", delta);
                    sseDataConsumer.accept(deltaPayload.toString());
                })
                .blockLast();
        return contentBuilder.toString();
    }

    ObjectNode buildFinalModelMessagePayload(UUID runId, String content) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("content", content == null ? "" : content);

        List<RunEventEntity> events = runEventRepository.findByRunIdOrderBySeqAsc(runId);
        structuredPayloadAssembler.assemble(events).ifPresent(structured -> payload.set("structured", structured));
        return payload;
    }
}
