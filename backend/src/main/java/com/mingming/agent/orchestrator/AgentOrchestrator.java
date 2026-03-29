package com.mingming.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.AgentRunEntity;
import com.mingming.agent.entity.ChatSessionEntity;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.repository.AgentRunRepository;
import com.mingming.agent.repository.ChatSessionRepository;
import com.mingming.agent.repository.RunEventRepository;
import com.mingming.agent.tool.LocalToolProvider;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private static final int MAX_CONTEXT_MESSAGES = 20;
    private static final int MAX_CONTEXT_CHARS = 12_000;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;
    private final ChatSessionRepository chatSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final RunEventRepository runEventRepository;
    private final List<LocalToolProvider> localToolProviders;

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
        try {
            e.setPayload(objectMapper.writeValueAsString(payload));
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
        List<Message> promptMessages = buildPromptMessages(sessionId, userText);

        ObjectNode userPayload = objectMapper.createObjectNode();
        userPayload.put("content", userText);
        appendEvent(runId, seq.getAndIncrement(), RunEventType.USER_MESSAGE, userPayload);

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

    List<Message> buildPromptMessages(UUID sessionId, String userText) {
        List<Message> historyMessages = loadSessionHistoryMessages(sessionId);
        List<Message> trimmedHistory = trimHistoryMessages(historyMessages);
        List<Message> promptMessages = new ArrayList<>(trimmedHistory);
        promptMessages.add(new UserMessage(userText));
        return promptMessages;
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
        ChatClient.builder(chatModel)
                .build()
                .prompt()
                .messages(promptMessages.toArray(new Message[0]))
                .tools(localToolProviders.stream().map(LocalToolProvider::toolBean).toArray())
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

        extractWeatherStructuredData(runId).ifPresent(structured -> payload.set("structured", objectMapper.valueToTree(structured)));
        return payload;
    }

    private java.util.Optional<Map<String, Object>> extractWeatherStructuredData(UUID runId) {
        List<RunEventEntity> events = runEventRepository.findByRunIdOrderBySeqAsc(runId);
        for (int i = events.size() - 1; i >= 0; i--) {
            RunEventEntity event = events.get(i);
            if (!RunEventType.TOOL_RESULT.name().equals(event.getType())) {
                continue;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(event.getPayload());
                if (!"get_weather".equals(root.path("tool").asText())) {
                    continue;
                }
                com.fasterxml.jackson.databind.JsonNode data = root.path("data");
                if (!data.path("ok").asBoolean(false)) {
                    continue;
                }
                return java.util.Optional.of(Map.of(
                        "schema", "weather.v1",
                        "city", data.path("city").asText(""),
                        "weather", data.path("weather").asText(""),
                        "temperature", data.path("temperature").asText(""),
                        "humidity", data.path("humidity").asText(""),
                        "windDirection", data.path("windDirection").asText(""),
                        "windPower", data.path("windPower").asText(""),
                        "reportTime", data.path("reportTime").asText("")));
            } catch (Exception ignored) {
            }
        }
        return java.util.Optional.empty();
    }
}
