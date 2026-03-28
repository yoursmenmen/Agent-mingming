package com.mingming.agent.mcp;

import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class McpHttpClient {

    private final WebClient webClient;

    public McpHttpClient(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    public WebClient client(String baseUrl, int timeoutMs) {
        // Timeout handling will be added when we wire real MCP requests.
        return webClient.mutate()
                .baseUrl(baseUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
