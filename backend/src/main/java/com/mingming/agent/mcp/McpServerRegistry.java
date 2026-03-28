package com.mingming.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.util.Collections;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class McpServerRegistry {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public McpServersConfig load() {
        try {
            InputStream in = new ClassPathResource("mcp/servers.yml").getInputStream();
            McpServersConfig cfg = yamlMapper.readValue(in, McpServersConfig.class);
            return cfg == null ? new McpServersConfig(Collections.emptyList()) : cfg;
        } catch (Exception e) {
            return new McpServersConfig(Collections.emptyList());
        }
    }
}
