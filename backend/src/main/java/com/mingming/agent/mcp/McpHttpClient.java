package com.mingming.agent.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

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
}
