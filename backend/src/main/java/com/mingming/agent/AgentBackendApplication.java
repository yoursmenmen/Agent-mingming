package com.mingming.agent;

import com.mingming.agent.rag.VectorRagProperties;
import com.mingming.agent.security.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SecurityProperties.class, VectorRagProperties.class})
public class AgentBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentBackendApplication.class, args);
    }
}
