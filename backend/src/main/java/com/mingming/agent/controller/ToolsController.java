package com.mingming.agent.controller;

import com.mingming.agent.tool.LocalToolProvider;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ToolsController {

    private final List<LocalToolProvider> localToolProviders;

    @GetMapping("/api/tools")
    public Object listLocalTools() {
        List<Map<String, Object>> tools = localToolProviders.stream()
                .map(LocalToolProvider::metadata)
                .sorted(java.util.Comparator.comparing(m -> m.name()))
                .map(meta -> Map.<String, Object>of(
                        "name", meta.name(),
                        "description", meta.description(),
                        "source", meta.source()))
                .toList();
        return Map.of("tools", tools);
    }
}
