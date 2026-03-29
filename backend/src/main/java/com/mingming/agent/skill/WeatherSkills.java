package com.mingming.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.tool.LocalToolProvider;
import com.mingming.agent.tool.ToolEventService;
import com.mingming.agent.tool.ToolMetadata;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WeatherSkills implements LocalToolProvider {

    private static final String TOOL_NAME = "get_weather";

    private final ToolEventService toolEventService;
    private final ObjectMapper objectMapper;
    private final String amapApiKey;
    private final String amapBaseUrl;
    private final HttpClient httpClient;

    public WeatherSkills(
            ToolEventService toolEventService,
            ObjectMapper objectMapper,
            @Value("${AMAP_WEATHER_API_KEY:}") String amapApiKey,
            @Value("${AMAP_WEATHER_BASE_URL:https://restapi.amap.com}") String amapBaseUrl) {
        this.toolEventService = toolEventService;
        this.objectMapper = objectMapper;
        this.amapApiKey = amapApiKey;
        this.amapBaseUrl = amapBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Tool(name = TOOL_NAME, description = "Get current weather by city name (e.g. 北京, 上海)")
    public Map<String, Object> getWeather(String city, ToolContext toolContext) {
        String normalizedCity = city == null ? "" : city.trim();
        toolEventService.recordToolCall(toolContext, TOOL_NAME, Map.of("city", normalizedCity));

        if (normalizedCity.isEmpty()) {
            Map<String, Object> result = Map.of(
                    "ok", false,
                    "error", "city is required");
            toolEventService.recordToolResult(toolContext, TOOL_NAME, result);
            return result;
        }
        if (amapApiKey == null || amapApiKey.isBlank()) {
            Map<String, Object> result = Map.of(
                    "ok", false,
                    "error", "AMAP_WEATHER_API_KEY is missing");
            toolEventService.recordToolResult(toolContext, TOOL_NAME, result);
            return result;
        }

        try {
            String encodedCity = URLEncoder.encode(normalizedCity, StandardCharsets.UTF_8);
            String url = amapBaseUrl + "/v3/weather/weatherInfo?extensions=base&city=" + encodedCity + "&key=" + amapApiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = objectMapper.readTree(response.body());

            if (!"1".equals(root.path("status").asText()) || root.path("lives").isEmpty()) {
                Map<String, Object> result = Map.of(
                        "ok", false,
                        "error", root.path("info").asText("weather query failed"),
                        "city", normalizedCity);
                toolEventService.recordToolResult(toolContext, TOOL_NAME, result);
                return result;
            }

            JsonNode live = root.path("lives").get(0);
            Map<String, Object> result = Map.of(
                    "ok", true,
                    "city", live.path("city").asText(normalizedCity),
                    "weather", live.path("weather").asText(""),
                    "temperature", live.path("temperature").asText(""),
                    "windDirection", live.path("winddirection").asText(""),
                    "windPower", live.path("windpower").asText(""),
                    "humidity", live.path("humidity").asText(""),
                    "reportTime", live.path("reporttime").asText(""));
            toolEventService.recordToolResult(toolContext, TOOL_NAME, result);
            return result;
        } catch (Exception ex) {
            log.warn("Weather tool failed", ex);
            Map<String, Object> result = Map.of(
                    "ok", false,
                    "error", "weather request failed: " + ex.getMessage(),
                    "city", normalizedCity);
            toolEventService.recordToolResult(toolContext, TOOL_NAME, result);
            return result;
        }
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                TOOL_NAME,
                "Get current weather by city name",
                "amap");
    }
}
