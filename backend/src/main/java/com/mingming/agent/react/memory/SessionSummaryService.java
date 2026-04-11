package com.mingming.agent.react.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.orchestrator.AgentOrchestrator;
import com.mingming.agent.repository.RunEventRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

@Service
public class SessionSummaryService {

    public record ConversationTurn(String userText, String assistantText) {}

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryService.class);
    private static final int MAX_FALLBACK_SUMMARY_CHARS = 1_200;
    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是会话记忆压缩器。请把历史对话压缩成简明摘要，要求：
            1. 只保留后续回答必须依赖的信息（目标、约束、已完成步骤、未完成事项、关键结论）。
            2. 不要复述无关寒暄。
            3. 用中文，条目化，尽量短。
            4. 输出纯文本，不要 JSON。
            """;

    private final RunEventRepository runEventRepository;
    private final AgentOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public SessionSummaryService(
            RunEventRepository runEventRepository,
            AgentOrchestrator orchestrator,
            ObjectMapper objectMapper) {
        this.runEventRepository = runEventRepository;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    public Optional<String> loadLatestSummary(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return runEventRepository.findLatestSessionSummaryEvent(sessionId)
                .map(RunEventEntity::getPayload)
                .map(this::extractContent)
                .filter(text -> !text.isBlank());
    }

    public Optional<String> refreshSummary(
            UUID runId,
            UUID sessionId,
            String previousSummary,
            List<ConversationTurn> recentTurns,
            ChatModel chatModel,
            AtomicInteger seqCounter) {
        if (runId == null || sessionId == null || seqCounter == null || recentTurns == null || recentTurns.isEmpty()) {
            return Optional.ofNullable(previousSummary).filter(s -> !s.isBlank());
        }

        String nextSummary = generateSummary(previousSummary, recentTurns, chatModel);
        if (nextSummary.isBlank()) {
            return Optional.ofNullable(previousSummary).filter(s -> !s.isBlank());
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sessionId", sessionId.toString());
        payload.put("sourceRunId", runId.toString());
        payload.put("turnCount", recentTurns.size());
        payload.put("content", nextSummary);

        orchestrator.appendEvent(runId, seqCounter.getAndIncrement(), RunEventType.SESSION_SUMMARY, payload);
        return Optional.of(nextSummary);
    }

    private String generateSummary(String previousSummary, List<ConversationTurn> recentTurns, ChatModel chatModel) {
        if (chatModel == null) {
            return fallbackSummary(previousSummary, recentTurns);
        }
        try {
            List<Message> promptMessages = List.of(
                    new SystemMessage(SUMMARY_SYSTEM_PROMPT),
                    new UserMessage(buildSummaryInput(previousSummary, recentTurns)));
            ChatResponse response = chatModel.call(new Prompt(promptMessages));
            String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                    ? ""
                    : String.valueOf(response.getResult().getOutput().getText());
            if (text == null || text.isBlank()) {
                return fallbackSummary(previousSummary, recentTurns);
            }
            return text.trim();
        } catch (Exception ex) {
            log.warn("summary generation failed, fallback to deterministic summary: {}", ex.getMessage());
            return fallbackSummary(previousSummary, recentTurns);
        }
    }

    private String buildSummaryInput(String previousSummary, List<ConversationTurn> recentTurns) {
        StringBuilder builder = new StringBuilder();
        builder.append("旧摘要：\n");
        builder.append(previousSummary == null || previousSummary.isBlank() ? "(无)" : previousSummary.trim());
        builder.append("\n\n新对话片段：\n");
        for (int i = 0; i < recentTurns.size(); i++) {
            ConversationTurn turn = recentTurns.get(i);
            builder.append("# Turn ").append(i + 1).append("\n");
            builder.append("User: ")
                    .append(turn.userText() == null ? "" : turn.userText().trim())
                    .append("\n");
            builder.append("Assistant: ")
                    .append(turn.assistantText() == null ? "" : turn.assistantText().trim())
                    .append("\n\n");
        }
        builder.append("请输出新的合并摘要。\n");
        return builder.toString();
    }

    private String fallbackSummary(String previousSummary, List<ConversationTurn> recentTurns) {
        StringBuilder builder = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            builder.append("历史摘要：").append(previousSummary.trim()).append("\n");
        }
        builder.append("最近进展：\n");
        for (ConversationTurn turn : recentTurns) {
            builder.append("- 用户：")
                    .append(turn.userText() == null ? "" : turn.userText().trim())
                    .append("\n");
            builder.append("- 助手：")
                    .append(turn.assistantText() == null ? "" : turn.assistantText().trim())
                    .append("\n");
        }
        String summary = builder.toString().trim();
        if (summary.length() <= MAX_FALLBACK_SUMMARY_CHARS) {
            return summary;
        }
        return summary.substring(0, MAX_FALLBACK_SUMMARY_CHARS);
    }

    private String extractContent(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readTree(payloadJson).path("content").asText("");
        } catch (Exception ex) {
            return "";
        }
    }
}
