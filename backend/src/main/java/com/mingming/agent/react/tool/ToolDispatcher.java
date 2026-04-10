package com.mingming.agent.react.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

@Component
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);
    private static final long CONFIRM_TIMEOUT_MS = 300_000L;

    private final Map<String, AgentTool> toolsByName;
    private final ToolConfirmRegistry confirmRegistry;
    private final ObjectMapper objectMapper;

    public ToolDispatcher(List<AgentTool> tools, ToolConfirmRegistry confirmRegistry, ObjectMapper objectMapper) {
        this.confirmRegistry = confirmRegistry;
        this.objectMapper = objectMapper;
        this.toolsByName = new HashMap<>();
        for (AgentTool tool : tools) {
            toolsByName.put(tool.name(), tool);
        }
    }

    public ToolResult dispatch(UUID runId, AssistantMessage.ToolCall toolCall, Consumer<String> sseConsumer) {
        String toolName = toolCall.name();
        AgentTool tool = toolsByName.get(toolName);
        if (tool == null) {
            return ToolResult.error("未找到工具：" + toolName);
        }
        Map<String, Object> args = parseArgs(toolCall.arguments());
        if (requiresConfirmation(tool, args)) {
            String confirmPayload = buildConfirmPayload(toolCall.id(), toolName, args);
            sseConsumer.accept(confirmPayload);
            log.info("等待用户确认 runId={} tool={} toolCallId={}", runId, toolName, toolCall.id());
            boolean approved = confirmRegistry.awaitConfirm(toolCall.id(), CONFIRM_TIMEOUT_MS);
            if (!approved) {
                return ToolResult.skipped("用户跳过或超时未响应");
            }
        }
        try {
            return tool.execute(args);
        } catch (Exception e) {
            log.error("工具执行异常 tool={}", toolName, e);
            return ToolResult.error("工具执行异常：" + e.getMessage());
        }
    }

    private boolean requiresConfirmation(AgentTool tool, Map<String, Object> args) {
        if ("file_op".equals(tool.name())) {
            String action = String.valueOf(args.getOrDefault("action", ""));
            return "write".equals(action) || "delete".equals(action);
        }
        if ("shell_exec".equals(tool.name())) {
            String command = String.valueOf(args.getOrDefault("command", ""));
            return !ShellTool.isWhitelisted(command);
        }
        return false;
    }

    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(argsJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("_raw", argsJson);
        }
    }

    private String buildConfirmPayload(String toolCallId, String toolName, Map<String, Object> args) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "TOOL_CONFIRM_REQUIRED");
            payload.put("toolCallId", toolCallId);
            payload.put("toolName", toolName);
            payload.put("args", args);
            payload.put("reason", buildReason(toolName, args));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"TOOL_CONFIRM_REQUIRED\",\"toolCallId\":\"" + toolCallId + "\"}";
        }
    }

    private String buildReason(String toolName, Map<String, Object> args) {
        if ("shell_exec".equals(toolName)) return "即将执行命令：" + args.getOrDefault("command", "(未知)");
        if ("file_op".equals(toolName)) {
            String action = String.valueOf(args.getOrDefault("action", ""));
            String path = String.valueOf(args.getOrDefault("path", "(未知)"));
            return ("delete".equals(action) ? "即将删除文件：" : "即将写入文件：") + path;
        }
        return "即将执行工具：" + toolName;
    }

    public List<AgentTool> getTools() {
        return List.copyOf(toolsByName.values());
    }
}
