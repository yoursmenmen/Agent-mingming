import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  applyMcpOnboardingPlan,
  confirmMcpAction,
  fetchMcpServers,
  fetchRagDocuments,
  fetchRagSources,
  fetchRagSyncStatus,
  fetchRunEventMetrics,
  fetchRunEvents,
  fetchSessionEvents,
  fetchTools,
  postChatStream,
  postToolConfirm,
  rejectMcpAction,
  setMcpServerEnabled,
  triggerRagSync,
} from '../services/api'
import { createStreamTimelineItem, mapRunEventToTimelineItem, mergeTimelineItems } from '../services/eventMapper'
import { parseStructuredPayload } from '../services/structured'
import { consumeSseStream } from '../services/sse'
import type { ChatMessage, StreamErrorEvent, StreamMessageEvent, StreamRunEvent } from '../types/chat'
import type {
  McpServerInfo,
  OnboardingPlanCard,
  PendingMcpAction,
  RagDocuments,
  RagSourceInfo,
  RagSyncStatus,
  RunEventMetrics,
  RunEventItem,
  RunStatus,
  TimelineItem,
  ToolInfo,
} from '../types/run'

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
  const runMetrics = ref<RunEventMetrics | null>(null)
  const isRefreshing = ref(false)
  const isRagRefreshing = ref(false)
  const isRagTriggering = ref(false)
  const isMcpRefreshing = ref(false)
  const mcpUpdatingServers = ref<string[]>([])
  const activeAssistantId = ref<string | null>(null)
  const streamSeq = ref(1)
  const runEventsPollingTimer = ref<number | null>(null)
  const runEventsPollingActive = ref(false)
  const handlingActionIds = ref<string[]>([])
  const localActionStates = ref<Record<string, 'PROCESSING' | 'CONFIRMED_EXECUTED' | 'CONFIRM_EXECUTION_FAILED' | 'REJECTED'>>({})
  const onboardingPlanState = ref<Record<string, 'READY' | 'APPLYING' | 'DISMISSED' | 'APPLIED'>>({})

  const timelineItems = computed(() => mergeTimelineItems(streamItems.value, historyItems.value))
  const timelineCount = computed(() => timelineItems.value.length)
  const timelineActionStates = computed(() => {
    const states = new Map<string, TimelineItem['actionState']>()
    for (const item of timelineItems.value) {
      if (!item.actionId || !item.actionState) {
        continue
      }
      states.set(item.actionId, item.actionState)
    }
    return states
  })
  const pendingMcpActions = computed<PendingMcpAction[]>(() => {
    const actionsById = new Map<string, PendingMcpAction>()
    for (const item of [...timelineItems.value].reverse()) {
      if (item.actionState === 'PENDING_CONFIRMATION' && item.actionId) {
        const timelineState = timelineActionStates.value.get(item.actionId)
        const localState = localActionStates.value[item.actionId]
        if (
          timelineState === 'CONFIRMED_EXECUTED' ||
          timelineState === 'CONFIRM_EXECUTION_FAILED' ||
          timelineState === 'REJECTED' ||
          localState === 'CONFIRMED_EXECUTED' ||
          localState === 'CONFIRM_EXECUTION_FAILED' ||
          localState === 'REJECTED'
        ) {
          continue
        }
        const isProcessing = localState === 'PROCESSING'
        actionsById.set(item.actionId, {
          actionId: item.actionId,
          tool: item.type,
          summary: isProcessing ? `${item.summary}（处理中）` : item.summary,
          createdAt: item.createdAt,
          state: isProcessing ? 'PROCESSING' : 'PENDING_CONFIRMATION',
        })
      }
    }
    return [...actionsById.values()].sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime())
  })

  const onboardingPlanCard = computed<OnboardingPlanCard | null>(() => {
    const latestPlan = findLatestOnboardingPlanEvent(timelineItems.value)
    if (!latestPlan) {
      return null
    }

    const planState = onboardingPlanState.value[latestPlan.planId]
    if (planState === 'DISMISSED' || planState === 'APPLIED') {
      return null
    }
    if (hasLaterOnboardingApplyEvent(timelineItems.value, latestPlan.seq, latestPlan.repoUrl)) {
      return null
    }

    return {
      ...latestPlan,
      state: planState ?? 'READY',
    }
  })
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

  function toTimelineEvents(events: RunEventItem[]): TimelineItem[] {
    return events
      .filter((event) => event.type !== 'MODEL_DELTA')
      .map(mapRunEventToTimelineItem)
  }

  function stopRunEventsPolling() {
    if (runEventsPollingTimer.value !== null) {
      window.clearInterval(runEventsPollingTimer.value)
      runEventsPollingTimer.value = null
    }
    runEventsPollingActive.value = false
  }

  async function syncCurrentRunEvents(currentRunId: string) {
    if (!currentRunId || currentRunId === '等待新会话' || currentRunId === '建立连接中…') {
      return
    }
    if (runEventsPollingActive.value) {
      return
    }

    runEventsPollingActive.value = true
    try {
      const events = await fetchRunEvents(currentRunId)
      historyItems.value = toTimelineEvents(events)
      if (events.length > 0 && streamItems.value.length > 0) {
        streamItems.value = streamItems.value.filter((item) => item.type === 'ERROR')
      }
    } catch {
      // ignore periodic polling failures; final refresh reconciles full history
    } finally {
      runEventsPollingActive.value = false
    }
  }

  function startRunEventsPolling(currentRunId: string) {
    stopRunEventsPolling()
    void syncCurrentRunEvents(currentRunId)
    runEventsPollingTimer.value = window.setInterval(() => {
      void syncCurrentRunEvents(currentRunId)
    }, 700)
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
      historyItems.value = toTimelineEvents(events)
      streamItems.value = []
      attachAssistantStructuredFromPersistedModelMessage(events)
      cleanupLocalActionStates()
      await refreshRunMetrics()
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

    await executeChatTurn(content, true)
  }

  async function executeChatTurn(content: string, showUserMessage: boolean) {
    if (showUserMessage) {
      pushUserMessage(content)
    }
    draft.value = ''
    errorMessage.value = ''
    runStatus.value = 'streaming'
    runId.value = '建立连接中…'
    streamItems.value = []
    streamSeq.value = 1
    if (showUserMessage) {
      streamItems.value.push(
        createStreamTimelineItem({
          id: createId('timeline-user'),
          seq: streamSeq.value++,
          type: 'USER_MESSAGE',
          createdAt: new Date().toISOString(),
          payload: JSON.stringify({ content }),
        }),
      )
    }
    createAssistantPlaceholder()

    let streamFailed = false

    try {
      const response = await postChatStream({ message: content, sessionId: sessionId.value ?? undefined })
      await consumeSseStream(response, (packet) => {
        const now = new Date().toISOString()

        if (packet.event === 'run') {
          const payload = JSON.parse(packet.data) as StreamRunEvent
          sessionId.value = payload.sessionId
          runId.value = payload.runId
          startRunEventsPolling(payload.runId)
          return
        }

        if (packet.event === 'event') {
          const raw = JSON.parse(packet.data) as Record<string, unknown>
          // Check if this is a structured event (not a text delta)
          if (typeof raw.type === 'string' && raw.type === 'TOOL_CONFIRM_REQUIRED') {
            // Add as stream timeline item
            streamItems.value.push(
              createStreamTimelineItem({
                id: createId('timeline-confirm'),
                seq: streamSeq.value++,
                type: 'TOOL_CONFIRM_REQUIRED',
                createdAt: new Date().toISOString(),
                payload: packet.data,
              }),
            )
            return
          }
          const payload = raw as unknown as StreamMessageEvent
          if (typeof payload.content === 'string' && payload.content.length > 0) {
            appendAssistantDelta(payload.content)
          }
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
      stopRunEventsPolling()
      activeAssistantId.value = null
    }
  }

  function summarizeConfirmResultForModel(confirmResponse: {
    actionId: string
    status: string
    server?: string
    tool?: string
    result?: Record<string, unknown>
    ok?: boolean
    error?: string
  }): string {
    const status = confirmResponse.status || 'UNKNOWN'
    const actionId = confirmResponse.actionId || 'unknown'
    const tool = confirmResponse.tool || 'unknown'
    const server = confirmResponse.server || 'unknown'
    const result = confirmResponse.result && typeof confirmResponse.result === 'object' ? confirmResponse.result : {}
    const exitCode = typeof result.exitCode === 'number' ? result.exitCode : null
    const stdout = typeof result.stdout === 'string' ? result.stdout : ''
    const stderr = typeof result.stderr === 'string' ? result.stderr : ''
    const executionMode = typeof result.executionMode === 'string' ? result.executionMode : ''

    if (status !== 'CONFIRMED_EXECUTED') {
      const error =
        (typeof confirmResponse.error === 'string' && confirmResponse.error) ||
        (typeof result.error === 'string' && result.error) ||
        'unknown error'
      return [
        '以下是命令确认执行结果，请简短告知用户执行失败，不要再次调用任何工具。',
        `actionId=${actionId}`,
        `server=${server}`,
        `tool=${tool}`,
        `status=${status}`,
        `error=${error}`,
      ].join('\n')
    }

    return [
      '以下是命令确认执行结果，请简短告知用户执行成功或失败，不要再次调用任何工具。',
      `actionId=${actionId}`,
      `server=${server}`,
      `tool=${tool}`,
      `status=${status}`,
      `executionMode=${executionMode}`,
      `exitCode=${exitCode === null ? 'N/A' : String(exitCode)}`,
      `stdout=${stdout.slice(0, 1600)}`,
      `stderr=${stderr.slice(0, 800)}`,
    ].join('\n')
  }

  async function refreshTools() {
    try {
      availableTools.value = await fetchTools()
    } catch {
      availableTools.value = []
    }
  }

  async function refreshRunMetrics() {
    try {
      runMetrics.value = await fetchRunEventMetrics(24)
    } catch {
      runMetrics.value = null
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

  function isHandlingAction(actionId: string): boolean {
    return handlingActionIds.value.includes(actionId)
  }

  async function handleMcpAction(actionId: string, decision: 'confirm' | 'reject') {
    if (!actionId || isHandlingAction(actionId)) {
      return
    }
    handlingActionIds.value = [...handlingActionIds.value, actionId]
    localActionStates.value = {
      ...localActionStates.value,
      [actionId]: 'PROCESSING',
    }
    try {
      if (decision === 'confirm') {
        const confirmResponse = await confirmMcpAction(actionId)
        localActionStates.value = {
          ...localActionStates.value,
          [actionId]: confirmResponse.status === 'CONFIRMED_EXECUTED' ? 'CONFIRMED_EXECUTED' : 'CONFIRM_EXECUTION_FAILED',
        }
        const followupMessage = summarizeConfirmResultForModel(confirmResponse)
        await executeChatTurn(followupMessage, false)
      } else {
        await rejectMcpAction(actionId)
        localActionStates.value = {
          ...localActionStates.value,
          [actionId]: 'REJECTED',
        }
      }
      await refreshRunEvents()
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '处理 MCP 动作失败'
      localActionStates.value = {
        ...localActionStates.value,
        [actionId]: 'CONFIRM_EXECUTION_FAILED',
      }
    } finally {
      handlingActionIds.value = handlingActionIds.value.filter((id) => id !== actionId)
    }
  }

  function cleanupLocalActionStates() {
    if (Object.keys(localActionStates.value).length === 0) {
      return
    }
    const next = { ...localActionStates.value }
    for (const [actionId, state] of Object.entries(localActionStates.value)) {
      const timelineState = timelineActionStates.value.get(actionId)
      if (
        timelineState === 'CONFIRMED_EXECUTED' ||
        timelineState === 'CONFIRM_EXECUTION_FAILED' ||
        timelineState === 'REJECTED' ||
        (state !== 'PROCESSING' && !timelineState)
      ) {
        delete next[actionId]
      }
    }
    localActionStates.value = next
  }

  async function handleToolConfirm(currentRunId: string, toolCallId: string, approved: boolean) {
    try {
      await postToolConfirm(currentRunId, toolCallId, approved)
    } catch (e) {
      console.error('工具确认失败', e)
    }
  }

  async function confirmPendingMcpAction(actionId: string) {
    await handleMcpAction(actionId, 'confirm')
  }

  async function rejectPendingMcpAction(actionId: string) {
    await handleMcpAction(actionId, 'reject')
  }

  async function applyOnboardingPlanFromCard(runInstall: boolean) {
    const card = onboardingPlanCard.value
    if (!card || onboardingPlanState.value[card.planId] === 'APPLYING') {
      return
    }

    onboardingPlanState.value = {
      ...onboardingPlanState.value,
      [card.planId]: 'APPLYING',
    }

    try {
      const result = await applyMcpOnboardingPlan({
        repoUrl: card.repoUrl,
        serverName: card.serverName,
        preferredTransport: card.preferredTransport,
        runInstall,
      })

      if (!result.ok) {
        const message = result.message || `执行接入失败：${result.status}`
        errorMessage.value = message
        onboardingPlanState.value = {
          ...onboardingPlanState.value,
          [card.planId]: 'READY',
        }
        return
      }

      onboardingPlanState.value = {
        ...onboardingPlanState.value,
        [card.planId]: 'APPLIED',
      }

      messages.value.push({
        id: createId('assistant-system'),
        role: 'assistant',
        content: [
          `已完成 MCP 接入：${card.serverName}`,
          `- repo: ${card.repoUrl}`,
          `- transport: ${card.preferredTransport}`,
          `- runInstall: ${runInstall ? 'true' : 'false'}`,
          `- ${result.message ?? '请重启后端以生效。'}`,
          '使用方式：请先刷新工具面板，然后在对话里让 Agent 调用该 MCP 工具。',
        ].join('\n'),
        createdAt: new Date().toISOString(),
        status: 'done',
      })

      await Promise.all([refreshMcpServers(), refreshTools(), refreshRunEvents()])
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '执行 MCP 接入失败'
      onboardingPlanState.value = {
        ...onboardingPlanState.value,
        [card.planId]: 'READY',
      }
    }
  }

  function dismissOnboardingPlanCard() {
    const card = onboardingPlanCard.value
    if (!card) {
      return
    }
    onboardingPlanState.value = {
      ...onboardingPlanState.value,
      [card.planId]: 'DISMISSED',
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
    void refreshRunMetrics()
  })

  function findLatestOnboardingPlanEvent(items: TimelineItem[]): (OnboardingPlanCard & { seq: number }) | null {
    const sorted = [...items].sort((a, b) => b.seq - a.seq)
    for (const item of sorted) {
      if (item.type !== 'TOOL_RESULT') {
        continue
      }
      const payload = parseToolResultPayload(item.rawPayload)
      if (!payload || payload.tool !== 'mcp_onboarding_plan' || !payload.data) {
        continue
      }

      const data = payload.data
      const repoUrl = asString(data.repoUrl)
      const planId = `${repoUrl}:${asString(data.serverName)}:${asString(data.cloneDir)}`
      if (!repoUrl || !planId) {
        continue
      }

      return {
        planId,
        repoUrl,
        source: asString(data.source) || 'unknown',
        owner: asString(data.owner),
        repo: asString(data.repo),
        serverName: asString(data.serverName),
        preferredTransport: asString(data.preferredTransport) || 'stdio',
        cloneDir: asString(data.cloneDir),
        startupCommand: asString(data.startupCommand),
        installCommands: asStringArray(data.installCommands),
        requiredEnv: asStringArray(data.requiredEnv),
        missingRequiredEnv: asStringArray(data.missingRequiredEnv),
        warnings: asStringArray(data.warnings),
        readyToApply: Boolean(data.readyToApply),
        createdAt: item.createdAt,
        state: 'READY',
        seq: item.seq,
      }
    }
    return null
  }

  function hasLaterOnboardingApplyEvent(items: TimelineItem[], seq: number, repoUrl: string): boolean {
    if (!repoUrl) {
      return false
    }
    for (const item of items) {
      if (item.seq <= seq || item.type !== 'TOOL_RESULT') {
        continue
      }
      const payload = parseToolResultPayload(item.rawPayload)
      if (!payload || payload.tool !== 'mcp_onboarding_apply' || !payload.data) {
        continue
      }
      const plan = payload.data.plan && typeof payload.data.plan === 'object' ? (payload.data.plan as Record<string, unknown>) : null
      const matched = asString(plan?.repoUrl) === repoUrl || asString(payload.data.repoUrl) === repoUrl
      if (!matched) {
        continue
      }
      const status = asString(payload.data.status)
      const ok = typeof payload.data.ok === 'boolean' ? payload.data.ok : false
      if ((status === 'APPLIED' || status === 'SUCCESS') && ok) {
        return true
      }
    }
    return false
  }

  function parseToolResultPayload(rawPayload: string): { tool: string; data?: Record<string, unknown> } | null {
    if (!rawPayload) {
      return null
    }
    try {
      const parsed = JSON.parse(rawPayload) as { tool?: unknown; data?: unknown }
      if (!parsed || typeof parsed !== 'object') {
        return null
      }
      const tool = typeof parsed.tool === 'string' ? parsed.tool : ''
      const data = parsed.data && typeof parsed.data === 'object' ? (parsed.data as Record<string, unknown>) : undefined
      return { tool, data }
    } catch {
      return null
    }
  }

  function asString(value: unknown): string {
    return typeof value === 'string' ? value : ''
  }

  function asStringArray(value: unknown): string[] {
    if (!Array.isArray(value)) {
      return []
    }
    return value.filter((item): item is string => typeof item === 'string')
  }

  onUnmounted(() => {
    stopRunEventsPolling()
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
    pendingMcpActions,
    onboardingPlanCard,
    availableTools,
    mcpServers,
    ragSyncStatus,
    ragSources,
    ragDocuments,
    runMetrics,
    statusLabel,
    errorMessage,
    isRefreshing,
    isRagRefreshing,
    isRagTriggering,
    isMcpRefreshing,
    mcpUpdatingServers,
    handlingActionIds,
    sendMessage,
    refreshRunEvents,
    refreshRunMetrics,
    refreshTools,
    refreshMcpServers,
    toggleMcpServer,
    confirmPendingMcpAction,
    rejectPendingMcpAction,
    handleToolConfirm,
    applyOnboardingPlanFromCard,
    dismissOnboardingPlanCard,
    refreshRagStatus,
    triggerRagSyncNow,
    formatTime,
  }
}
