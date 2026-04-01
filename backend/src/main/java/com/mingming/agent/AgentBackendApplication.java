package com.mingming.agent;

import com.mingming.agent.rag.VectorRagProperties;
import com.mingming.agent.rag.SyncSchedulerProperties;
import com.mingming.agent.rag.source.UrlSourceProperties;
import com.mingming.agent.security.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({SecurityProperties.class, VectorRagProperties.class, UrlSourceProperties.class, SyncSchedulerProperties.class})
public class AgentBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentBackendApplication.class, args);
    }
}
