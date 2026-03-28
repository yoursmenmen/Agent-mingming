package com.mingming.agent.skill;

import java.time.Instant;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class TimeSkills {

    @Tool(name = "now", description = "Get current time in ISO-8601 (UTC) and epoch millis")
    public Map<String, Object> now() {
        Instant now = Instant.now();
        return Map.of(
                "isoUtc", now.toString(),
                "epochMillis", now.toEpochMilli()
        );
    }
}
