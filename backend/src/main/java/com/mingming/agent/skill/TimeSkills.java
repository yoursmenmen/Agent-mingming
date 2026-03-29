package com.mingming.agent.skill;

import com.mingming.agent.tool.ToolEventService;
import com.mingming.agent.tool.LocalToolProvider;
import com.mingming.agent.tool.ToolMetadata;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TimeSkills implements LocalToolProvider {

    private final ToolEventService toolEventService;

    @Tool(name = "now", description = "Get current time in ISO-8601 (UTC) and epoch millis")
    public Map<String, Object> now(ToolContext toolContext) {
        toolEventService.recordToolCall(toolContext, "now", Map.of());
        Instant now = Instant.now();
        Map<String, Object> result = Map.of(
                "isoUtc", now.toString(),
                "epochMillis", now.toEpochMilli()
        );
        toolEventService.recordToolResult(toolContext, "now", result);
        return result;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "now",
                "Get current time in ISO-8601 (UTC) and epoch millis",
                "local");
    }
}
