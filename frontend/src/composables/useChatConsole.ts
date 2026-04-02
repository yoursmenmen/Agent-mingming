import { computed, onMounted, ref } from 'vue'
import { fetchMcpServers, fetchRagDocuments, fetchRagSources, fetchRagSyncStatus, fetchRunEvents, fetchSessionEvents, fetchTools, postChatStream, setMcpServerEnabled, triggerRagSync } from '../services/api'
import { createStreamTimelineItem, mapRunEventToTimelineItem, mergeTimelineItems } from '../services/eventMapper'
import { parseStructuredPayload } from '../services/structured'
import { consumeSseStream } from '../services/sse'
import type { ChatMessage, StreamErrorEvent, StreamMessageEvent, StreamRunEvent } from '../types/chat'
import type { McpServerInfo, RagDocuments, RagSourceInfo, RagSyncStatus, RunEventItem, RunStatus, TimelineItem, ToolInfo } from '../types/run'

type ModelMessagePayload = {
  content?: unknown
  structured?: unknown
}

function createId(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`
}

function parseModelMessagePayload(payload: string): ModelMessagePayload | null {
  try {
    const parsed = JSON.parse(payload)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return null
    }
    return parsed as ModelMessagePayload
  } catch {
    return null
  }
}

export function useChatConsole() {
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
  const sessionId = ref<string | null>(null)
  const runId = ref('等待新会话')
  const runStatus = ref<RunStatus>('idle')
  const streamItems = ref<TimelineItem[]>([])
  const historyItems = ref<TimelineItem[]>([])
  const errorMessage = ref('')
  const availableTools = ref<ToolInfo[]>([])
  const mcpServers = ref<McpServerInfo[]>([])
  const ragSyncStatus = ref<RagSyncStatus | null>(null)
  const ragSources = ref<RagSourceInfo[]>([])
  const ragDocuments = ref<RagDocuments>({ localDocs: [], urlDocs: [] })
  const isRefreshing = ref(false)
  const isRagRefreshing = ref(false)
  const isRagTriggering = ref(false)
  const isMcpRefreshing = ref(false)
  const mcpUpdatingServers = ref<string[]>([])
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

  function appendAssistantDelta(delta: string) {
    if (!activeAssistantId.value) {
      return
    }

    const target = messages.value.find((item) => item.id === activeAssistantId.value)
    if (!target) {
      return
    }

    const current = target.content === '思考中…' ? '' : target.content
    target.content = `${current}${delta}`
    target.status = 'streaming'
  }

  function attachAssistantStructuredFromPersistedModelMessage(events: RunEventItem[]) {
    if (!activeAssistantId.value) {
      return
    }

    const assistant = messages.value.find((item) => item.id === activeAssistantId.value)
    if (!assistant) {
      return
    }

    const latestModelMessage = events
      .filter((event) => event.type === 'MODEL_MESSAGE' && event.runId === runId.value)
      .sort((a, b) => b.seq - a.seq)[0]

    if (!latestModelMessage) {
      return
    }

    const payload = parseModelMessagePayload(latestModelMessage.payload)
    if (!payload) {
      return
    }

    if (typeof payload.content === 'string' && payload.content.trim().length > 0) {
      assistant.content = payload.content
    }

    if ('structured' in payload) {
      assistant.structured = parseStructuredPayload(payload.structured)
    }
  }

  async function refreshRunEvents() {
    const hasSession = Boolean(sessionId.value)
    const hasRun = Boolean(runId.value && runId.value !== '等待新会话')
    if (!hasSession && !hasRun) {
      return
    }

    isRefreshing.value = true
    try {
      const events = sessionId.value
        ? await fetchSessionEvents(sessionId.value)
        : await fetchRunEvents(runId.value)
      historyItems.value = events.map(mapRunEventToTimelineItem)
      streamItems.value = []
      attachAssistantStructuredFromPersistedModelMessage(events)
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
    streamSeq.value = 1
    createAssistantPlaceholder()

    let streamFailed = false

    try {
      const response = await postChatStream({
        message: content,
        sessionId: sessionId.value ?? undefined,
      })
      await consumeSseStream(response, (packet) => {
        const now = new Date().toISOString()

        if (packet.event === 'run') {
          const payload = JSON.parse(packet.data) as StreamRunEvent
          sessionId.value = payload.sessionId
          runId.value = payload.runId
          return
        }

        if (packet.event === 'event') {
          const payload = JSON.parse(packet.data) as StreamMessageEvent
          appendAssistantDelta(payload.content)
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

  async function refreshTools() {
    try {
      availableTools.value = await fetchTools()
    } catch {
      availableTools.value = []
    }
  }

  async function refreshMcpServers() {
    isMcpRefreshing.value = true
    try {
      mcpServers.value = await fetchMcpServers()
    } catch (error) {
      mcpServers.value = []
      errorMessage.value = error instanceof Error ? error.message : '获取 MCP 服务失败'
    } finally {
      isMcpRefreshing.value = false
    }
  }

  async function toggleMcpServer(server: string, enabled: boolean) {
    if (!server || mcpUpdatingServers.value.includes(server)) {
      return
    }
    mcpUpdatingServers.value = [...mcpUpdatingServers.value, server]
    try {
      await setMcpServerEnabled(server, enabled)
      await refreshMcpServers()
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '更新 MCP 开关失败'
    } finally {
      mcpUpdatingServers.value = mcpUpdatingServers.value.filter((name) => name !== server)
    }
  }

  async function refreshRagStatus() {
    isRagRefreshing.value = true
    try {
      const [status, sources, documents] = await Promise.all([fetchRagSyncStatus(), fetchRagSources(), fetchRagDocuments()])
      ragSyncStatus.value = status
      ragSources.value = sources
      ragDocuments.value = documents
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '获取 RAG 状态失败'
    } finally {
      isRagRefreshing.value = false
    }
  }

  async function triggerRagSyncNow() {
    if (isRagTriggering.value) {
      return
    }
    isRagTriggering.value = true
    try {
      const result = await triggerRagSync()
      ragSyncStatus.value = result.status
      await refreshRagStatus()
      if (!result.accepted) {
        errorMessage.value = 'RAG 同步未被接受，可能有任务正在执行'
      }
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '触发 RAG 同步失败'
    } finally {
      isRagTriggering.value = false
    }
  }

  onMounted(() => {
    void refreshTools()
    void refreshMcpServers()
    void refreshRagStatus()
  })

  function formatTime(value: string): string {
    return new Date(value).toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
  }

  return {
    messages,
    draft,
    sessionId,
    runId,
    runStatus,
    timelineItems,
    timelineCount,
    availableTools,
    mcpServers,
    ragSyncStatus,
    ragSources,
    ragDocuments,
    statusLabel,
    errorMessage,
    isRefreshing,
    isRagRefreshing,
    isRagTriggering,
    isMcpRefreshing,
    mcpUpdatingServers,
    sendMessage,
    refreshRunEvents,
    refreshTools,
    refreshMcpServers,
    toggleMcpServer,
    refreshRagStatus,
    triggerRagSyncNow,
    formatTime,
  }
}
