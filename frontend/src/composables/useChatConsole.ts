import { computed, ref } from 'vue'
import { fetchRunEvents, postChatStream } from '../services/api'
import { createStreamTimelineItem, mapRunEventToTimelineItem, mergeTimelineItems } from '../services/eventMapper'
import { consumeSseStream } from '../services/sse'
import type { ChatMessage, StreamErrorEvent, StreamMessageEvent, StreamRunEvent } from '../types/chat'
import type { RunStatus, TimelineItem } from '../types/run'

function createId(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`
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

  return {
    messages,
    draft,
    runId,
    runStatus,
    timelineItems,
    timelineCount,
    statusLabel,
    errorMessage,
    isRefreshing,
    sendMessage,
    refreshRunEvents,
    formatTime,
  }
}
