<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { TimelineItem } from '../types/run'
import ToolConfirmCard from './ToolConfirmCard.vue'

const props = defineProps<{
  sessionId: string | null
  runId: string
  timelineItems: TimelineItem[]
  formatTime: (value: string) => string
}>()

const emit = defineEmits<{
  toolConfirm: [toolCallId: string, approved: boolean]
}>()

type TimelineRound = {
  id: string
  title: string
  startedAt: string
  items: TimelineItem[]
  turnNumber: number
  isSystem: boolean
}

const expandedRounds = ref<Record<string, boolean>>({})

const roundGroups = computed<TimelineRound[]>(() => {
  const rounds: TimelineRound[] = []
  let current: TimelineRound | null = null
  let turnNumber = 0

  for (const item of props.timelineItems) {
    if (item.type === 'USER_MESSAGE') {
      turnNumber += 1
      const next: TimelineRound = {
        id: `round-${item.id}-${item.seq}`,
        title: buildRoundTitle(item.summary),
        startedAt: item.createdAt,
        items: [item],
        turnNumber,
        isSystem: false,
      }
      if (current) {
        rounds.push(current)
      }
      current = next
      continue
    }

    if (!current) {
      current = {
        id: `round-system-${item.id}-${item.seq}`,
        title: '系统事件',
        startedAt: item.createdAt,
        items: [],
        turnNumber: 0,
        isSystem: true,
      }
    }
    current.items.push(item)
  }

  if (current) {
    rounds.push(current)
  }

  return rounds
})

watch(
  roundGroups,
  (groups) => {
    const nextState: Record<string, boolean> = {}
    for (const group of groups) {
      nextState[group.id] = expandedRounds.value[group.id] ?? false
    }

    const hasExpanded = Object.values(nextState).some(Boolean)
    if (!hasExpanded && groups.length > 0) {
      const latest = groups[groups.length - 1]
      nextState[latest.id] = true
    }
    expandedRounds.value = nextState
  },
  { immediate: true },
)

function toggleRound(roundId: string): void {
  expandedRounds.value = {
    ...expandedRounds.value,
    [roundId]: !expandedRounds.value[roundId],
  }
}

function isRoundExpanded(roundId: string): boolean {
  return Boolean(expandedRounds.value[roundId])
}

function roundLabel(round: TimelineRound): string {
  if (round.isSystem) {
    return '系统轮次'
  }
  return `第 ${round.turnNumber} 轮`
}

function buildRoundTitle(summary: string): string {
  const trimmed = summary.trim()
  if (!trimmed) {
    return '用户提问'
  }
  if (trimmed.length <= 42) {
    return trimmed
  }
  return `${trimmed.slice(0, 42)}...`
}

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

function parseToolCallId(rawPayload: string): string {
  try { return (JSON.parse(rawPayload) as { toolCallId?: string }).toolCallId ?? '' }
  catch { return '' }
}
function parseToolName(rawPayload: string): string {
  try { return (JSON.parse(rawPayload) as { toolName?: string }).toolName ?? '未知工具' }
  catch { return '未知工具' }
}
function parseProp(rawPayload: string, key: string): string {
  try { return String((JSON.parse(rawPayload) as Record<string, unknown>)[key] ?? '') }
  catch { return '' }
}
function parseArgs(rawPayload: string): Record<string, unknown> {
  try { return (JSON.parse(rawPayload) as { args?: Record<string, unknown> }).args ?? {} }
  catch { return {} }
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

      <div v-else class="timeline-rounds">
        <section v-for="round in roundGroups" :key="round.id" class="timeline-round">
          <button
            class="timeline-round-header"
            type="button"
            :aria-expanded="isRoundExpanded(round.id)"
            @click="toggleRound(round.id)"
          >
            <div class="timeline-round-labels">
              <strong>{{ roundLabel(round) }}</strong>
              <span>{{ round.title }}</span>
            </div>
            <div class="timeline-round-meta">
              <span>{{ round.items.length }} 条事件</span>
              <span>{{ props.formatTime(round.startedAt) }}</span>
            </div>
          </button>

          <ol v-show="isRoundExpanded(round.id)" class="timeline-list">
            <li v-for="item in round.items" :key="`${item.source}-${item.id}-${item.seq}`" class="timeline-item">
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
                <ToolConfirmCard
                  v-if="item.type === 'TOOL_CONFIRM_REQUIRED'"
                  :tool-call-id="parseToolCallId(item.rawPayload)"
                  :tool-name="parseToolName(item.rawPayload)"
                  :reason="parseProp(item.rawPayload, 'reason')"
                  :args="parseArgs(item.rawPayload)"
                  :run-id="props.runId"
                  :on-confirm="(id, approved) => emit('toolConfirm', id, approved)"
                />
              </div>
            </li>
          </ol>
        </section>
      </div>
    </div>
  </section>
</template>
