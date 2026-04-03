<script setup lang="ts">
import type { TimelineItem } from '../types/run'

const props = defineProps<{
  sessionId: string | null
  runId: string
  timelineItems: TimelineItem[]
  formatTime: (value: string) => string
}>()

function badgeLabel(item: TimelineItem): string {
  if (item.type === 'MCP_CONFIRM_RESULT') {
    if (item.actionState === 'CONFIRMED_EXECUTED') {
      return '确认已执行'
    }
    if (item.actionState === 'CONFIRM_EXECUTION_FAILED') {
      return '确认执行失败'
    }
    if (item.actionState === 'REJECTED') {
      return '已拒绝'
    }
  }
  return ''
}

function badgeTone(item: TimelineItem): 'success' | 'danger' | 'muted' {
  if (item.type === 'MCP_CONFIRM_RESULT') {
    if (item.actionState === 'CONFIRMED_EXECUTED') {
      return 'success'
    }
    if (item.actionState === 'CONFIRM_EXECUTION_FAILED') {
      return 'danger'
    }
    if (item.actionState === 'REJECTED') {
      return 'muted'
    }
  }
  return 'muted'
}
</script>

<template>
  <section class="panel timeline-panel">
    <div class="panel-header compact">
      <div>
        <p class="panel-kicker">Trace</p>
        <h2>事件时间线</h2>
      </div>
      <p class="panel-tip">
        {{ props.sessionId ? `GET /api/sessions/${props.sessionId}/events` : `GET /api/runs/${props.runId}/events` }}
      </p>
    </div>

    <div class="timeline-panel-body pane-body">
      <div v-if="!props.timelineItems.length" class="empty-state">发送第一条消息后，这里会出现会话级事件时间线。</div>

      <ol v-else class="timeline-list">
        <li v-for="item in props.timelineItems" :key="`${item.source}-${item.id}-${item.seq}`" class="timeline-item">
          <div class="timeline-node"></div>
          <div class="timeline-card">
            <div class="timeline-meta">
              <strong>{{ item.type }}</strong>
              <div class="timeline-meta-right">
                <span
                  v-if="badgeLabel(item)"
                  class="timeline-event-badge"
                  :data-tone="badgeTone(item)"
                >
                  {{ badgeLabel(item) }}
                </span>
                <span>{{ props.formatTime(item.createdAt) }}</span>
              </div>
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
