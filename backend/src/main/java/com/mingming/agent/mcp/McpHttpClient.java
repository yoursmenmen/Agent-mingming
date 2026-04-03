package com.mingming.agent.mcp;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class McpHttpClient {

    private static final Logger log = LoggerFactory.getLogger(McpHttpClient.class);

    private final WebClient.Builder builder;

    public WebClient client(String baseUrl, int timeoutMs) {
        // Timeout handling will be added when we wire real MCP requests.
        return builder.build().mutate()
                .baseUrl(baseUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public Map<String, Object> postJson(McpServerConfig server, Map<String, Object> payload) {
        if (server == null) {
            throw new IllegalArgumentException("server config is required");
        }
        return postJson(server.url(), server.timeoutMs(), payload, server.auth(), server.name());
    }

    public Map<String, Object> postJson(String baseUrl, int timeoutMs, Map<String, Object> payload) {
        return postJson(baseUrl, timeoutMs, payload, null, "unknown");
    }

    private Map<String, Object> postJson(
            String baseUrl,
            int timeoutMs,
            Map<String, Object> payload,
            McpAuthConfig auth,
            String serverName) {
        int effectiveTimeout = timeoutMs > 0 ? timeoutMs : 10_000;
        return client(baseUrl, effectiveTimeout)
                .post()
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuthHeaders(headers, auth, serverName))
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMillis(effectiveTimeout))
                .block();
    }

    private void applyAuthHeaders(HttpHeaders headers, McpAuthConfig auth, String serverName) {
        if (auth == null || auth.type() == null || auth.type().isBlank()) {
            return;
        }

        String type = auth.type().trim().toLowerCase();
        switch (type) {
            case "none" -> {
                return;
            }
            case "bearer" -> {
                String token = resolveSecret(auth);
                if (token.isBlank()) {
                    throw new IllegalStateException("mcp auth token missing for server: " + safe(serverName));
                }
                headers.setBearerAuth(token);
            }
            case "apikey", "api_key" -> {
                String token = resolveSecret(auth);
                if (token.isBlank()) {
                    throw new IllegalStateException("mcp api key missing for server: " + safe(serverName));
                }
                String headerName = auth.headerName() == null || auth.headerName().isBlank() ? "x-api-key" : auth.headerName();
                headers.set(headerName, token);
            }
            default -> {
                log.warn("Unsupported MCP auth type; server={}, type={}", safe(serverName), safe(auth.type()));
            }
        }
    }

    private String resolveSecret(McpAuthConfig auth) {
        if (auth == null) {
            return "";
        }
        if (auth.tokenEnv() != null && !auth.tokenEnv().isBlank()) {
            String value = System.getenv(auth.tokenEnv().trim());
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        if (auth.token() != null && !auth.token().isBlank()) {
            return auth.token().trim();
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
