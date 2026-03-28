package com.mingming.agent.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "agent.security")
public class SecurityProperties {

    /**
     * API token for simple auth. Must be sent as: Authorization: Bearer <token>
     */
    private String apiToken;
}
