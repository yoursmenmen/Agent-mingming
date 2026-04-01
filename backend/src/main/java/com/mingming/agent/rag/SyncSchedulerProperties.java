package com.mingming.agent.rag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "agent.rag.sync.scheduler")
public class SyncSchedulerProperties {

    private boolean enabled = false;

    private String cron = "0 0 3 ? * SUN";

    private String zone = "Asia/Shanghai";
}
