package com.mingming.agent.skill;

import com.mingming.agent.tool.ToolEventService;
import com.mingming.agent.tool.LocalToolProvider;
import com.mingming.agent.tool.ToolMetadata;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MathSkills implements LocalToolProvider {

    private final ToolEventService toolEventService;

    @Tool(name = "add", description = "Add two numbers")
    public Map<String, Object> add(double a, double b) {
        toolEventService.recordToolCall("add", Map.of("a", a, "b", b));
        Map<String, Object> result = Map.of("result", a + b);
        toolEventService.recordToolResult("add", result);
        return result;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                "add",
                "Add two numbers",
                "local");
    }
}
