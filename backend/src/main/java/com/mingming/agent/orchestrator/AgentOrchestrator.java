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
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;
    private final ChatSessionRepository chatSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final RunEventRepository runEventRepository;

    public record RunInit(UUID sessionId, UUID runId) {}

    public RunInit startRun(String model, Double temperature, Double topP, String systemPromptVersion) {
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionId);
        session.setCreatedAt(OffsetDateTime.now());
        chatSessionRepository.save(session);

        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setSessionId(sessionId);
        run.setCreatedAt(OffsetDateTime.now());
        run.setModel(model);
        run.setTemperature(temperature);
        run.setTopP(topP);
        run.setSystemPromptVersion(systemPromptVersion);
        agentRunRepository.save(run);

        return new RunInit(sessionId, runId);
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
    public void runOnce(UUID runId, String userText, java.util.function.Consumer<String> sseDataConsumer) {
        AtomicInteger seq = new AtomicInteger(1);

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        String content = chatModel == null
                ? "当前未配置 DashScope 模型，已切换到本地回退响应。你可以先继续联调前后端链路，配置好 AI_DASHSCOPE_API_KEY 后再接入真实大模型输出。"
                : ChatClient.builder(chatModel)
                        .build()
                        .prompt()
                        .messages(new UserMessage(userText))
                        .call()
                        .content();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("content", content);
        appendEvent(runId, seq.getAndIncrement(), RunEventType.MODEL_MESSAGE, payload);

        sseDataConsumer.accept(payload.toString());
    }
}
