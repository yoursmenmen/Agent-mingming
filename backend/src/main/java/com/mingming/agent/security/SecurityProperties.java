package com.mingming.agent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.security")
public class SecurityProperties {

    /**
     * API token for simple auth. Must be sent as: Authorization: Bearer <token>
     */
    private String apiToken;

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }
}
