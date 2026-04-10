package com.mingming.agent.react;

import com.mingming.agent.react.tool.AgentTool;
import com.mingming.agent.react.tool.FetchTool;
import com.mingming.agent.react.tool.FileTool;
import com.mingming.agent.react.tool.ShellTool;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentToolConfig {

    @Bean
    public List<AgentTool> agentTools() {
        return List.of(new FetchTool(), new FileTool(), new ShellTool());
    }
}
