package com.mingming.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpRuntimeToolCallbackFactoryTest {

    @Mock
    private McpToolService mcpToolService;

    @Test
    void prepareRuntimeTools_shouldApplyDenyListByDefault() {
        when(mcpToolService.listTools())
                .thenReturn(Map.of(
                        "tools",
                        List.of(
                                Map.of("server", "local-ops", "name", "fetch_page", "description", "fetch"),
                                Map.of("server", "local-ops", "name", "run_local_command", "description", "exec")),
                        "errors",
                        List.of()));

        McpRuntimeToolCallbackFactory factory =
                new McpRuntimeToolCallbackFactory(mcpToolService, new ObjectMapper(), true, "", "run_local_command", 32);

        McpRuntimeToolCallbackFactory.RuntimeToolBundle bundle = factory.prepareRuntimeTools();

        assertThat(bundle.callbacks()).hasSize(1);
        assertThat(bundle.boundTools()).hasSize(1);
        assertThat(bundle.blockedTools()).hasSize(1);
        assertThat(bundle.blockedTools().get(0).get("reason")).isEqualTo("denied-by-policy");
    }

    @Test
    void prepareRuntimeTools_shouldApplyAllowList() {
        when(mcpToolService.listTools())
                .thenReturn(Map.of(
                        "tools",
                        List.of(
                                Map.of("server", "local-ops", "name", "fetch_page"),
                                Map.of("server", "local-ops", "name", "k8s_cluster_status")),
                        "errors",
                        List.of()));

        McpRuntimeToolCallbackFactory factory =
                new McpRuntimeToolCallbackFactory(mcpToolService, new ObjectMapper(), true, "local-ops:fetch_page", "", 32);

        McpRuntimeToolCallbackFactory.RuntimeToolBundle bundle = factory.prepareRuntimeTools();

        assertThat(bundle.callbacks()).hasSize(1);
        assertThat(bundle.boundTools()).hasSize(1);
        assertThat(bundle.boundTools().get(0).get("tool")).isEqualTo("fetch_page");
        assertThat(bundle.blockedTools()).hasSize(1);
        assertThat(bundle.blockedTools().get(0).get("reason")).isEqualTo("not-in-allowlist");
    }

    @Test
    void prepareRuntimeTools_shouldExtractRequiredFieldsFromJsonSchema() {
        when(mcpToolService.listTools())
                .thenReturn(Map.of(
                        "tools",
                        List.of(Map.of(
                                "server",
                                "local-ops",
                                "name",
                                "fetch_page",
                                "inputSchema",
                                "{\"type\":\"object\",\"required\":[\"url\",\"maxChars\"]}")),
                        "errors",
                        List.of()));

        McpRuntimeToolCallbackFactory factory =
                new McpRuntimeToolCallbackFactory(mcpToolService, new ObjectMapper(), true, "", "", 32);

        McpRuntimeToolCallbackFactory.RuntimeToolBundle bundle = factory.prepareRuntimeTools();

        assertThat(bundle.callbacks()).hasSize(1);
        assertThat(bundle.boundTools()).hasSize(1);
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) bundle.boundTools().get(0).get("required");
        assertThat(required).containsExactly("url", "maxChars");
    }
}
