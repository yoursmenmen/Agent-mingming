<script setup lang="ts">
import { computed, ref } from 'vue'
import { DEV_TOKEN, fetchRunEvents, postChatStream } from './services/api'
import { createStreamTimelineItem, mapRunEventToTimelineItem, mergeTimelineItems } from './services/eventMapper'
import { consumeSseStream } from './services/sse'
import type { ChatMessage, StreamErrorEvent, StreamMessageEvent, StreamRunEvent } from './types/chat'
import type { RunStatus, TimelineItem } from './types/run'

const messages = ref<ChatMessage[]>([
  {
    id: 'welcome',
    role: 'assistant',
    content: '你好，我已经准备好接入后端。你可以直接发一条消息，我会展示流式结果、Run 状态和事件时间线。',
    createdAt: new Date().toISOString(),
    status: 'done',
  },
])
const draft = ref('')
const runId = ref('等待新会话')
const runStatus = ref<RunStatus>('idle')
const streamItems = ref<TimelineItem[]>([])
const historyItems = ref<TimelineItem[]>([])
const errorMessage = ref('')
const isRefreshing = ref(false)
const activeAssistantId = ref<string | null>(null)
const streamSeq = ref(1)

const timelineItems = computed(() => mergeTimelineItems(streamItems.value, historyItems.value))
const timelineCount = computed(() => timelineItems.value.length)
const statusLabel = computed(() => {
  switch (runStatus.value) {
    case 'streaming':
      return '运行中'
    case 'done':
      return '已完成'
    case 'error':
      return '异常'
    default:
      return '待命中'
  }
})

function createId(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`
}

function pushUserMessage(content: string) {
  messages.value.push({
    id: createId('user'),
    role: 'user',
    content,
    createdAt: new Date().toISOString(),
    status: 'done',
  })
}

function createAssistantPlaceholder() {
  const id = createId('assistant')
  activeAssistantId.value = id
  messages.value.push({
    id,
    role: 'assistant',
    content: '思考中…',
    createdAt: new Date().toISOString(),
    status: 'streaming',
  })
}

function updateAssistantMessage(content: string, status: ChatMessage['status']) {
  if (!activeAssistantId.value) {
    return
  }

  const target = messages.value.find((item) => item.id === activeAssistantId.value)
  if (!target) {
    return
  }

  target.content = content
  target.status = status
}

async function refreshRunEvents() {
  if (!runId.value || runId.value === '等待新会话') {
    return
  }

  isRefreshing.value = true
  try {
    const events = await fetchRunEvents(runId.value)
    historyItems.value = events.map(mapRunEventToTimelineItem)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '拉取运行事件失败'
  } finally {
    isRefreshing.value = false
  }
}

async function sendMessage() {
  const content = draft.value.trim()
  if (!content || runStatus.value === 'streaming') {
    return
  }

  pushUserMessage(content)
  draft.value = ''
  errorMessage.value = ''
  runStatus.value = 'streaming'
  runId.value = '建立连接中…'
  streamItems.value = []
  historyItems.value = []
  streamSeq.value = 1
  createAssistantPlaceholder()

  let streamFailed = false

  try {
    const response = await postChatStream({ message: content })
    await consumeSseStream(response, (packet) => {
      const now = new Date().toISOString()

      if (packet.event === 'run') {
        const payload = JSON.parse(packet.data) as StreamRunEvent
        runId.value = payload.runId
        return
      }

      if (packet.event === 'event') {
        const payload = JSON.parse(packet.data) as StreamMessageEvent
        updateAssistantMessage(payload.content, 'streaming')
        streamItems.value.push(
          createStreamTimelineItem({
            id: createId('timeline'),
            seq: streamSeq.value++,
            type: 'MODEL_MESSAGE',
            createdAt: now,
            payload: packet.data,
          }),
        )
        return
      }

      const payload = JSON.parse(packet.data) as StreamErrorEvent
      errorMessage.value = payload.message || '流式请求失败'
      updateAssistantMessage(errorMessage.value, 'error')
      runStatus.value = 'error'
      streamFailed = true
      streamItems.value.push(
        createStreamTimelineItem({
          id: createId('timeline-error'),
          seq: streamSeq.value++,
          type: 'ERROR',
          createdAt: now,
          payload: packet.data,
        }),
      )
    })

    if (!streamFailed) {
      runStatus.value = 'done'
      const assistant = messages.value.find((item) => item.id === activeAssistantId.value)
      if (assistant) {
        assistant.status = 'done'
      }
      await refreshRunEvents()
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '发送消息失败'
    runStatus.value = 'error'
    updateAssistantMessage(errorMessage.value, 'error')
  } finally {
    activeAssistantId.value = null
  }
}

function formatTime(value: string): string {
  return new Date(value).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}
</script>

<template>
  <div class="app-shell">
    <header class="hero-card">
      <div>
        <p class="eyebrow">Agent_mm 控制台</p>
        <h1>莓粉风 AI Agent 工作台</h1>
        <p class="hero-copy">
          先把聊天主链路跑通，再用事件时间线观察每次 run 的落库结果。
        </p>
      </div>
      <div class="hero-meta">
        <div class="meta-pill">
          <span class="meta-label">开发 Token</span>
          <code>{{ DEV_TOKEN }}</code>
        </div>
        <div class="meta-pill status-pill" :data-status="runStatus">
          <span class="status-dot"></span>
          {{ statusLabel }}
        </div>
      </div>
    </header>

    <main class="workspace">
      <section class="panel chat-panel">
        <div class="panel-header">
          <div>
            <p class="panel-kicker">Chat</p>
            <h2>对话区</h2>
          </div>
          <p class="panel-tip">后端接口：POST /api/chat/stream</p>
        </div>

        <div class="message-list">
          <article
            v-for="message in messages"
            :key="message.id"
            class="message"
            :class="[
              `message--${message.role}`,
              message.status ? `message--${message.status}` : '',
              message.role === 'assistant' && message.status === 'done' ? 'message--assistant-done' : '',
            ]"
          >
            <div class="message-topline">
              <div class="message-badge">{{ message.role === 'user' ? '你' : 'Agent' }}</div>
              <span v-if="message.role === 'assistant' && message.status === 'streaming'" class="message-live-chip">
                实时生成中
              </span>
            </div>
            <p>{{ message.content }}</p>
            <time>{{ formatTime(message.createdAt) }}</time>
          </article>
        </div>

        <div v-if="errorMessage" class="error-banner">
          {{ errorMessage }}
        </div>

        <form class="composer" @submit.prevent="sendMessage">
          <textarea
            v-model="draft"
            class="composer-input"
            rows="4"
            placeholder="试试输入：帮我总结这个项目当前能力"
          />
          <div class="composer-footer">
            <span>使用固定开发 token，经由 Vite 代理访问后端</span>
            <button class="send-button" type="submit" :disabled="runStatus === 'streaming'">
              {{ runStatus === 'streaming' ? '生成中…' : '发送消息' }}
            </button>
          </div>
        </form>
      </section>

      <aside class="sidebar">
        <section class="panel status-panel">
          <div class="panel-header compact">
            <div>
              <p class="panel-kicker">Run</p>
              <h2>运行状态</h2>
            </div>
            <button class="ghost-button" type="button" @click="refreshRunEvents" :disabled="isRefreshing">
              {{ isRefreshing ? '刷新中…' : '刷新事件' }}
            </button>
          </div>

          <dl class="status-grid">
            <div>
              <dt>当前状态</dt>
              <dd>{{ statusLabel }}</dd>
            </div>
            <div>
              <dt>Run ID</dt>
              <dd class="run-id">{{ runId }}</dd>
            </div>
            <div>
              <dt>时间线条数</dt>
              <dd>{{ timelineCount }}</dd>
            </div>
            <div>
              <dt>视图模式</dt>
              <dd>聊天 + 时间线</dd>
            </div>
          </dl>
        </section>

        <section class="panel timeline-panel">
          <div class="panel-header compact">
            <div>
              <p class="panel-kicker">Trace</p>
              <h2>事件时间线</h2>
            </div>
            <p class="panel-tip">GET /api/runs/{{ runId }}/events</p>
          </div>

          <div v-if="!timelineItems.length" class="empty-state">
            发送第一条消息后，这里会出现 run event 时间线。
          </div>

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
        </section>
      </aside>
    </main>
  </div>
</template>
