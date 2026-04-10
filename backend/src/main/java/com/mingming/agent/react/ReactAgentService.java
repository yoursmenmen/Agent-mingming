package com.mingming.agent.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.orchestrator.AgentOrchestrator;
import com.mingming.agent.react.tool.AgentTool;
import com.mingming.agent.react.tool.ToolDispatcher;
import com.mingming.agent.react.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ReactAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentService.class);

    private static final String BASE_SYSTEM_PROMPT = """
            你是一个智能助手，可以使用工具完成复杂任务（如读取网页、执行命令、操作文件）。
            当需要信息或执行操作时，请直接调用对应工具。
            当你已能给出最终答案时，直接回答即可，无需任何特殊标记。
            若任务无法自动完成，请明确告知用户需要手动执行哪些步骤。
            """;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AgentOrchestrator orchestrator;
    private final ToolDispatcher toolDispatcher;
    private final ObjectMapper objectMapper;

    public ReactAgentService(
            ObjectProvider<ChatModel> chatModelProvider,
            AgentOrchestrator orchestrator,
            ToolDispatcher toolDispatcher,
            ObjectMapper objectMapper) {
        this.chatModelProvider = chatModelProvider;
        this.orchestrator = orchestrator;
        this.toolDispatcher = toolDispatcher;
        this.objectMapper = objectMapper;
    }

    public void execute(
            UUID runId,
            UUID sessionId,
            String userText,
            TerminationPolicy policy,
            Consumer<String> sseConsumer) {

        AtomicInteger seq = new AtomicInteger(1);
        long startedAt = System.currentTimeMillis();
        int consecutiveErrors = 0;

        // 记录用户消息事件
        appendEvent(runId, seq, RunEventType.USER_MESSAGE, Map.of("content", userText));

        // 构建初始消息（含工具 schema 的 system message）
        List<Message> messages = buildInitialMessages(sessionId, userText);

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            String fallback = "当前未配置 DashScope 模型，无法执行 Agent 任务。请配置 AI_DASHSCOPE_API_KEY 后重试。";
            sseConsumer.accept(jsonContent(fallback));
            appendEvent(runId, seq, RunEventType.RUN_TERMINATED,
                    Map.of("reason", "NO_MODEL", "totalTurns", 0));
            return;
        }

        for (int turn = 1; turn <= policy.maxTurns(); turn++) {
            if (System.currentTimeMillis() - startedAt > policy.maxDurationMs()) {
                appendEvent(runId, seq, RunEventType.RUN_TERMINATED,
                        Map.of("reason", "TIMEOUT", "totalTurns", turn - 1));
                sseConsumer.accept(jsonContent("执行超时（" + policy.maxDurationMs() / 1000 + "秒），共完成 " + (turn - 1) + " 轮。"));
                return;
            }

            log.info("ReAct turn={} runId={}", turn, runId);

            StringBuilder contentBuilder = new StringBuilder();
            ChatResponse lastResponse;
            try {
                lastResponse = streamAndCollect(chatModel, messages, contentBuilder, sseConsumer);
            } catch (Exception e) {
                log.error("LLM 调用异常 turn={} runId={}", turn, runId, e);
                consecutiveErrors++;
                if (consecutiveErrors >= policy.maxConsecutiveErrors()) {
                    appendEvent(runId, seq, RunEventType.RUN_TERMINATED,
                            Map.of("reason", "CONSECUTIVE_ERRORS", "totalTurns", turn));
                    sseConsumer.accept(jsonContent("连续调用失败（" + consecutiveErrors + "次），已终止。错误：" + e.getMessage()));
                    return;
                }
                continue;
            }
            consecutiveErrors = 0;

            String assistantText = contentBuilder.toString();

            // 落库本轮 LLM 输出
            appendEvent(runId, seq, RunEventType.MODEL_OUTPUT,
                    Map.of("content", assistantText, "turnIndex", turn));

            AssistantMessage assistantMsg = lastResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();

            // 没有工具调用 → 最终答案
            if (toolCalls == null || toolCalls.isEmpty()) {
                appendEvent(runId, seq, RunEventType.MODEL_MESSAGE, Map.of("content", assistantText));
                appendEvent(runId, seq, RunEventType.RUN_COMPLETED,
                        Map.of("totalTurns", turn,
                                "totalDurationMs", System.currentTimeMillis() - startedAt));
                return;
            }

            // 执行工具调用
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                appendEvent(runId, seq, RunEventType.TOOL_CALL, Map.of(
                        "tool", toolCall.name(),
                        "toolCallId", toolCall.id(),
                        "args", toolCall.arguments(),
                        "turnIndex", turn));

                sseConsumer.accept(jsonContent("🔧 调用工具：" + toolCall.name()));

                ToolResult result = toolDispatcher.dispatch(runId, toolCall, sseConsumer);

                appendEvent(runId, seq, RunEventType.TOOL_RESULT, Map.of(
                        "tool", toolCall.name(),
                        "toolCallId", toolCall.id(),
                        "success", result.success(),
                        "output", result.output() != null ? result.output() : "",
                        "error", result.error() != null ? result.error() : "",
                        "turnIndex", turn));

                String summary = result.success()
                        ? "✅ " + toolCall.name() + " 执行成功"
                        : "❌ " + toolCall.name() + "：" + result.error();
                sseConsumer.accept(jsonContent(summary));

                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), result.toJson()));
            }

            // 追加历史，进入下一轮
            messages.add(AssistantMessage.builder()
                    .content(assistantText)
                    .toolCalls(toolCalls)
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(toolResponses)
                    .build());
        }

        // 达到最大轮次
        appendEvent(runId, seq, RunEventType.RUN_TERMINATED,
                Map.of("reason", "MAX_TURNS", "totalTurns", policy.maxTurns()));
        sseConsumer.accept(jsonContent("已达到最大轮次（" + policy.maxTurns() + " 轮），请缩小问题范围后重试。"));
    }

    private List<Message> buildInitialMessages(UUID sessionId, String userText) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(BASE_SYSTEM_PROMPT));
        messages.addAll(orchestrator.buildPromptMessages(sessionId, userText));
        return messages;
    }

    /**
     * 将 AgentTool 列表转为 Spring AI ToolCallback，并封装进 ToolCallingChatOptions。
     * internalToolExecutionEnabled=false：Spring AI 只声明工具，不自动执行；
     * 执行权留在 ReactAgentService 的显式 loop 里（由 ToolDispatcher 分级处理）。
     *
     * 扩展性：新增工具只需实现 AgentTool 并注册 Bean，此处自动感知，无需改动。
     */
    private Prompt buildPromptWithTools(List<Message> messages) {
        List<AgentTool> tools = toolDispatcher.getTools();
        if (tools.isEmpty()) {
            return new Prompt(messages);
        }
        List<ToolCallback> callbacks = tools.stream()
                .map(tool -> FunctionToolCallback
                        .builder(tool.name(), (String args) -> "{}")
                        .description(tool.description())
                        .inputSchema(tool.inputSchema())
                        .build())
                .collect(java.util.stream.Collectors.toList());

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .build();
        return new Prompt(messages, options);
    }

    private ChatResponse streamAndCollect(
            ChatModel chatModel,
            List<Message> messages,
            StringBuilder contentBuilder,
            Consumer<String> sseConsumer) {

        Flux<ChatResponse> stream = chatModel.stream(buildPromptWithTools(messages));
        ChatResponse[] lastRef = new ChatResponse[1];
        stream.doOnNext(chunk -> {
            if (chunk == null || chunk.getResult() == null) return;
            lastRef[0] = chunk;
            AssistantMessage msg = chunk.getResult().getOutput();
            if (msg == null) return;
            String delta = msg.getText();
            if (delta != null && !delta.isBlank()) {
                contentBuilder.append(delta);
                sseConsumer.accept(jsonContent(delta));
            }
        }).blockLast();
        if (lastRef[0] == null) throw new IllegalStateException("模型流式调用未返回任何数据");
        return lastRef[0];
    }

    private void appendEvent(UUID runId, AtomicInteger seq, RunEventType type, Map<String, Object> data) {
        ObjectNode payload = objectMapper.createObjectNode();
        data.forEach((k, v) -> {
            if (v instanceof String s) payload.put(k, s);
            else if (v instanceof Integer i) payload.put(k, i);
            else if (v instanceof Long l) payload.put(k, l);
            else if (v instanceof Boolean b) payload.put(k, b);
            else payload.put(k, String.valueOf(v));
        });
        orchestrator.appendEvent(runId, seq.getAndIncrement(), type, payload);
    }

    private String jsonContent(String content) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("content", content);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"content\":\"" + content.replace("\"", "\\\"") + "\"}";
        }
    }
}
