package com.mingming.agent.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class McpHttpClient {

    private final WebClient.Builder builder;

    public WebClient client(String baseUrl, int timeoutMs) {
        // Timeout handling will be added when we wire real MCP requests.
        return builder.build().mutate()
                .baseUrl(baseUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public Map<String, Object> postJson(String baseUrl, int timeoutMs, Map<String, Object> payload) {
        int effectiveTimeout = timeoutMs > 0 ? timeoutMs : 10_000;
        return client(baseUrl, effectiveTimeout)
                .post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMillis(effectiveTimeout))
                .block();
    }
}
