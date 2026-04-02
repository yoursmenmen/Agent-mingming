package com.mingming.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class McpServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistry.class);

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    void initLog() {
        McpServersConfig config = load();
        int size = config.servers() == null ? 0 : config.servers().size();
        log.info("MCP registry initialized: configuredServers={}", size);
    }

    public McpServersConfig load() {
        try {
            InputStream in = new ClassPathResource("mcp/servers.yml").getInputStream();
            McpServersConfig cfg = yamlMapper.readValue(in, McpServersConfig.class);
            McpServersConfig result = cfg == null ? new McpServersConfig(Collections.emptyList()) : cfg;
            int size = result.servers() == null ? 0 : result.servers().size();
            log.debug("MCP servers loaded from classpath: path=mcp/servers.yml, count={}", size);
            return result;
        } catch (Exception e) {
            log.warn("MCP servers load failed: path=mcp/servers.yml, message={}", e.getMessage());
            return new McpServersConfig(Collections.emptyList());
        }
    }
}
