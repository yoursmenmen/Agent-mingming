<script setup lang="ts">
import type { TimelineItem } from '../types/run'

defineProps<{
  sessionId: string | null
  runId: string
  timelineItems: TimelineItem[]
  formatTime: (value: string) => string
}>()
</script>

<template>
  <section class="panel timeline-panel">
    <div class="panel-header compact">
      <div>
        <p class="panel-kicker">Trace</p>
        <h2>事件时间线</h2>
      </div>
      <p class="panel-tip">
        {{ sessionId ? `GET /api/sessions/${sessionId}/events` : `GET /api/runs/${runId}/events` }}
      </p>
    </div>

    <div class="timeline-panel-body pane-body">
      <div v-if="!timelineItems.length" class="empty-state">发送第一条消息后，这里会出现会话级事件时间线。</div>

      <ol v-else class="timeline-list">
        <li v-for="item in timelineItems" :key="`${item.source}-${item.id}-${item.seq}`" class="timeline-item">
          <div class="timeline-node"></div>
          <div class="timeline-card">
            <div class="timeline-meta">
              <strong>{{ item.type }}</strong>
              <span>{{ formatTime(item.createdAt) }}</span>
            </div>
            <p>{{ item.summary }}</p>
            <details>
              <summary>查看原始 payload</summary>
              <pre>{{ item.rawPayload }}</pre>
            </details>
          </div>
        </li>
      </ol>
    </div>
  </section>
</template>
