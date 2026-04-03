package com.mingming.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mingming.agent.mcp.McpOnboardingService;
import com.mingming.agent.mcp.McpToolService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class McpControllerTest {

    @Mock
    private McpToolService mcpToolService;

    @Mock
    private McpOnboardingService mcpOnboardingService;

    @Test
    void listTools_shouldDelegateToService() {
        McpController controller = new McpController(mcpToolService, mcpOnboardingService);
        Map<String, Object> expected = Map.of("tools", List.of(Map.of("name", "search_docs")), "errors", List.of());
        when(mcpToolService.listTools()).thenReturn(expected);

        Object result = controller.listTools();

        assertThat(result).isEqualTo(expected);
        verify(mcpToolService).listTools();
    }

    @Test
    void callTool_shouldDelegateToService() {
        McpController controller = new McpController(mcpToolService, mcpOnboardingService);
        McpController.McpToolCallRequest request =
                new McpController.McpToolCallRequest("docs", "search_docs", Map.of("query", "mcp"));
        Map<String, Object> expected = Map.of("server", "docs", "tool", "search_docs", "result", Map.of());
        when(mcpToolService.callTool("docs", "search_docs", Map.of("query", "mcp"), "api:mcp-tools-call"))
                .thenReturn(expected);

        Object result = controller.callTool(request);

        assertThat(result).isEqualTo(expected);
        verify(mcpToolService).callTool("docs", "search_docs", Map.of("query", "mcp"), "api:mcp-tools-call");
    }

    @Test
    void listServers_shouldDelegateToService() {
        McpController controller = new McpController(mcpToolService, mcpOnboardingService);
        Map<String, Object> expected = Map.of("servers", List.of());
        when(mcpToolService.listServersWithTools()).thenReturn(expected);

        Object result = controller.listServers();

        assertThat(result).isEqualTo(expected);
        verify(mcpToolService).listServersWithTools();
    }

    @Test
    void setServerEnabled_shouldDelegateToService() {
        McpController controller = new McpController(mcpToolService, mcpOnboardingService);
        McpController.McpServerEnabledRequest request = new McpController.McpServerEnabledRequest("local-ops", true);
        Map<String, Object> expected = Map.of("name", "local-ops", "effectiveEnabled", true);
        when(mcpToolService.setServerEnabled("local-ops", true)).thenReturn(expected);

        Object result = controller.setServerEnabled(request);

        assertThat(result).isEqualTo(expected);
        verify(mcpToolService).setServerEnabled("local-ops", true);
    }

    @Test
    void onboardingPlan_shouldDelegateToService() {
        McpController controller = new McpController(mcpToolService, mcpOnboardingService);
        McpController.McpOnboardingPlanRequest request =
                new McpController.McpOnboardingPlanRequest("https://github.com/arjun1194/insta-mcp", "insta", "stdio");
        Map<String, Object> expected = Map.of("readyToApply", true);
        when(mcpOnboardingService.createPlan(request.repoUrl(), request.serverName(), request.preferredTransport()))
                .thenReturn(expected);

        Object result = controller.createOnboardingPlan(request);

        assertThat(result).isEqualTo(expected);
        verify(mcpOnboardingService).createPlan(request.repoUrl(), request.serverName(), request.preferredTransport());
    }
}
