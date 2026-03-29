package com.mingming.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mingming.agent.tool.LocalToolProvider;
import com.mingming.agent.tool.ToolMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolsControllerTest {

    @Mock
    private LocalToolProvider nowProvider;

    @Mock
    private LocalToolProvider addProvider;

    @Test
    void listLocalTools_shouldReturnNowAndAdd() {
        when(nowProvider.metadata()).thenReturn(new ToolMetadata(
                "now",
                "Get current time in ISO-8601 (UTC) and epoch millis",
                "local"));
        when(addProvider.metadata()).thenReturn(new ToolMetadata(
                "add",
                "Add two numbers",
                "local"));

        ToolsController controller = new ToolsController(List.of(nowProvider, addProvider));

        Object result = controller.listLocalTools();
        assertThat(result).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) payload.get("tools");

        assertThat(tools).extracting(t -> t.get("name")).contains("now", "add");
    }
}
