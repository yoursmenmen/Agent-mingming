package com.mingming.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.tool.ToolEventService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
class WeatherSkillsTest {

    @Mock
    private ToolEventService toolEventService;

    @Test
    void getWeather_shouldReturnErrorWhenApiKeyMissing() {
        WeatherSkills weatherSkills = new WeatherSkills(
                toolEventService,
                new ObjectMapper(),
                "",
                "https://restapi.amap.com");
        ToolContext toolContext = new ToolContext(Map.of());

        Map<String, Object> result = weatherSkills.getWeather("北京", toolContext);

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("AMAP_WEATHER_API_KEY is missing");
        verify(toolEventService).recordToolCall(any(), org.mockito.ArgumentMatchers.eq("get_weather"), org.mockito.ArgumentMatchers.eq(Map.of("city", "北京")));
        verify(toolEventService).recordToolResult(any(), org.mockito.ArgumentMatchers.eq("get_weather"), org.mockito.ArgumentMatchers.eq(result));
    }
}
