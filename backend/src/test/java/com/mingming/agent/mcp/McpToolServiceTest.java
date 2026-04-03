package com.mingming.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.repository.RunEventRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpToolServiceTest {

    @Mock
    private McpServerRegistry registry;

    @Mock
    private McpHttpClient mcpHttpClient;

    @Mock
    private RunEventRepository runEventRepository;

    @Test
    void listTools_shouldAggregateToolsFromEnabledHttpServers() {
        McpServerConfig server = new McpServerConfig("docs", "http", "http://localhost:9000", "sse", true, 5000);
        when(registry.load()).thenReturn(new McpServersConfig(List.of(server)));
        when(mcpHttpClient.postJson(eq("http://localhost:9000"), eq(5000), anyMap()))
                .thenReturn(Map.of(
                        "jsonrpc", "2.0",
                        "result", Map.of("tools", List.of(Map.of("name", "search_docs", "description", "Search docs")))));

        McpToolService service = new McpToolService(registry, mcpHttpClient, runEventRepository, new ObjectMapper());

        Map<String, Object> payload = service.listTools();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) payload.get("tools");

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("name")).isEqualTo("search_docs");
        assertThat(tools.get(0).get("server")).isEqualTo("docs");
    }

    @Test
    void callTool_shouldCallTargetServerAndReturnResult() {
        McpServerConfig server = new McpServerConfig("docs", "http", "http://localhost:9000", "sse", true, 5000);
        when(registry.load()).thenReturn(new McpServersConfig(List.of(server)));
        when(mcpHttpClient.postJson(eq("http://localhost:9000"), eq(5000), anyMap()))
                .thenReturn(Map.of(
                        "jsonrpc", "2.0",
                        "result", Map.of("content", List.of(Map.of("type", "text", "text", "ok")))));

        McpToolService service = new McpToolService(registry, mcpHttpClient, runEventRepository, new ObjectMapper());
        Map<String, Object> result = service.callTool("docs", "search_docs", Map.of("query", "mcp"));

        assertThat(result.get("server")).isEqualTo("docs");
        assertThat(result.get("tool")).isEqualTo("search_docs");
        assertThat(result).containsKey("result");
        verify(mcpHttpClient).postJson(eq("http://localhost:9000"), eq(5000), anyMap());
    }

    @Test
    void callTool_shouldRejectUnknownServer() {
        when(registry.load()).thenReturn(new McpServersConfig(List.of()));
        McpToolService service = new McpToolService(registry, mcpHttpClient, runEventRepository, new ObjectMapper());

        assertThatThrownBy(() -> service.callTool("missing", "search_docs", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enabled HTTP MCP server not found");
    }

    @Test
    void listServersWithTools_shouldIncludeToolsForEnabledServer() {
        McpServerConfig server = new McpServerConfig("docs", "http", "http://localhost:9000", "none", true, 5000);
        when(registry.load()).thenReturn(new McpServersConfig(List.of(server)));
        when(mcpHttpClient.postJson(eq("http://localhost:9000"), eq(5000), anyMap()))
                .thenReturn(Map.of("jsonrpc", "2.0", "result", Map.of("tools", List.of(Map.of("name", "fetch_page")))));

        McpToolService service = new McpToolService(registry, mcpHttpClient, runEventRepository, new ObjectMapper());
        Map<String, Object> result = service.listServersWithTools();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) result.get("servers");
        assertThat(servers).hasSize(1);
        assertThat(servers.get(0).get("name")).isEqualTo("docs");
        assertThat(servers.get(0).get("lastStatus")).isEqualTo("SUCCESS");
        assertThat(((List<?>) servers.get(0).get("tools"))).hasSize(1);
    }

    @Test
    void setServerEnabled_shouldOverrideConfiguredValue() {
        McpServerConfig server = new McpServerConfig("docs", "http", "http://localhost:9000", "none", false, 5000);
        when(registry.load()).thenReturn(new McpServersConfig(List.of(server)));

        McpToolService service = new McpToolService(registry, mcpHttpClient, runEventRepository, new ObjectMapper());
        Map<String, Object> payload = service.setServerEnabled("docs", true);

        assertThat(payload.get("name")).isEqualTo("docs");
        assertThat(payload.get("configuredEnabled")).isEqualTo(false);
        assertThat(payload.get("effectiveEnabled")).isEqualTo(true);
    }
}
