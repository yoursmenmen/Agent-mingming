package com.mingming.agent.rag;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.rag.sync.scheduler", name = "enabled", havingValue = "true")
public class RagSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(RagSyncScheduler.class);

    private final SyncStatusService syncStatusService;

    @Scheduled(cron = "${agent.rag.sync.scheduler.cron:0 0 3 ? * SUN}", zone = "${agent.rag.sync.scheduler.zone:Asia/Shanghai}")
    public void scheduledSync() {
        boolean accepted = syncStatusService.triggerScheduled();
        if (!accepted) {
            log.info("Skip scheduled RAG sync because another sync is running or trigger is rejected");
        }
    }
}
