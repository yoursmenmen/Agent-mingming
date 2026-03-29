package com.mingming.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.tool.ToolEventService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        Map<String, Object> result = weatherSkills.getWeather("北京");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo("AMAP_WEATHER_API_KEY is missing");
        verify(toolEventService).recordToolCall("get_weather", Map.of("city", "北京"));
        verify(toolEventService).recordToolResult("get_weather", result);
    }
}
