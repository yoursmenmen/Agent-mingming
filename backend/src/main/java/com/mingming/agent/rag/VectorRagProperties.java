package com.mingming.agent.rag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "agent.rag.vector")
public class VectorRagProperties {

    private boolean enabled = true;

    private String docsRoot = "../docs";

    private String embeddingModel = "text-embedding-v3";

    private String embeddingVersion = "2026-03";
}
