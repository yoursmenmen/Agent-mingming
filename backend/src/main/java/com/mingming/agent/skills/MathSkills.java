package com.mingming.agent.skills;

import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class MathSkills {

    @Tool(name = "add", description = "Add two numbers")
    public Map<String, Object> add(double a, double b) {
        return Map.of("result", a + b);
    }
}
