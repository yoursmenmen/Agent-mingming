import type { RunEventItem, TimelineItem } from '../types/run'

export function safeParsePayload(payload: string): unknown {
  try {
    return JSON.parse(payload)
  } catch {
    return payload
  }
}

function summarizeRetrievalPayload(payload: unknown): string | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }

  const retrievalPayload = payload as {
    query?: unknown
    strategy?: unknown
    vectorHitCount?: unknown
    bm25HitCount?: unknown
    finalHitCount?: unknown
    hitCount?: unknown
    hits?: unknown
  }
  const query =
    typeof retrievalPayload.query === 'string' && retrievalPayload.query.trim().length > 0
      ? retrievalPayload.query
      : '(empty query)'
  const hits = Array.isArray(retrievalPayload.hits) ? retrievalPayload.hits : []
  const representativeDocPath =
    hits.find(
      (hit): hit is { docPath: string } =>
        Boolean(hit && typeof hit === 'object' && typeof (hit as { docPath?: unknown }).docPath === 'string'),
    )?.docPath ?? 'N/A'
  const legacyHitCount =
    typeof retrievalPayload.hitCount === 'number' && Number.isFinite(retrievalPayload.hitCount)
      ? retrievalPayload.hitCount
      : hits.length
  const strategy =
    typeof retrievalPayload.strategy === 'string' && retrievalPayload.strategy.trim().length > 0
      ? retrievalPayload.strategy
      : 'unknown'
  const vectorHitCount =
    typeof retrievalPayload.vectorHitCount === 'number' && Number.isFinite(retrievalPayload.vectorHitCount)
      ? retrievalPayload.vectorHitCount
      : legacyHitCount
  const bm25HitCount =
    typeof retrievalPayload.bm25HitCount === 'number' && Number.isFinite(retrievalPayload.bm25HitCount)
      ? retrievalPayload.bm25HitCount
      : legacyHitCount
  const finalHitCount =
    typeof retrievalPayload.finalHitCount === 'number' && Number.isFinite(retrievalPayload.finalHitCount)
      ? retrievalPayload.finalHitCount
      : legacyHitCount
  const firstHitSource =
    hits.find(
      (hit): hit is { source: string } =>
        Boolean(hit && typeof hit === 'object' && typeof (hit as { source?: unknown }).source === 'string'),
    )?.source ?? 'N/A'

  const countDetails = `策略: ${strategy} | 向量: ${vectorHitCount} | BM25: ${bm25HitCount} | 最终: ${finalHitCount}`
  if (finalHitCount > 0) {
    return `命中 ${finalHitCount} 条 | ${countDetails} | 查询: ${query} | 首条来源: ${firstHitSource} | 代表文档: ${representativeDocPath}`
  }

  return `未命中 | ${countDetails} | 查询: ${query} | 首条来源: ${firstHitSource} | 代表文档: ${representativeDocPath}`
}

function summarizeRagSyncPayload(payload: unknown): string | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }

  const ragPayload = payload as {
    phase?: unknown
    trigger?: unknown
    error?: unknown
    stats?: {
      inserted?: unknown
      updated?: unknown
      softDeleted?: unknown
      unchanged?: unknown
    }
  }

  const phase = typeof ragPayload.phase === 'string' && ragPayload.phase.trim().length > 0 ? ragPayload.phase : 'unknown'
  const trigger = typeof ragPayload.trigger === 'string' && ragPayload.trigger.trim().length > 0 ? ragPayload.trigger : 'unknown'
  const stats = ragPayload.stats && typeof ragPayload.stats === 'object' ? ragPayload.stats : {}
  const inserted = typeof stats.inserted === 'number' && Number.isFinite(stats.inserted) ? stats.inserted : 0
  const updated = typeof stats.updated === 'number' && Number.isFinite(stats.updated) ? stats.updated : 0
  const softDeleted = typeof stats.softDeleted === 'number' && Number.isFinite(stats.softDeleted) ? stats.softDeleted : 0
  const unchanged = typeof stats.unchanged === 'number' && Number.isFinite(stats.unchanged) ? stats.unchanged : 0
  const error = typeof ragPayload.error === 'string' ? ragPayload.error : ''

  if (phase === 'failed') {
    return `RAG 同步失败 | 触发: ${trigger} | 原因: ${error || '未知错误'}`
  }

  if (phase === 'completed') {
    return `RAG 同步完成 | 触发: ${trigger} | 新增: ${inserted} | 更新: ${updated} | 软删除: ${softDeleted} | 未变更: ${unchanged}`
  }

  if (phase === 'started') {
    return `RAG 同步开始 | 触发: ${trigger}`
  }

  return `RAG 同步事件 | 阶段: ${phase} | 触发: ${trigger}`
}

function summarizeMcpToolsBoundPayload(payload: unknown): string | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }

  const mcpPayload = payload as {
    localToolCount?: unknown
    mcpToolCount?: unknown
    totalToolCount?: unknown
    injectedMcpTools?: unknown
    blockedMcpTools?: unknown
    mcpDiscoveryErrors?: unknown
  }

  const localToolCount =
    typeof mcpPayload.localToolCount === 'number' && Number.isFinite(mcpPayload.localToolCount)
      ? mcpPayload.localToolCount
      : 0
  const mcpToolCount =
    typeof mcpPayload.mcpToolCount === 'number' && Number.isFinite(mcpPayload.mcpToolCount) ? mcpPayload.mcpToolCount : 0
  const totalToolCount =
    typeof mcpPayload.totalToolCount === 'number' && Number.isFinite(mcpPayload.totalToolCount)
      ? mcpPayload.totalToolCount
      : localToolCount + mcpToolCount
  const blockedCount = Array.isArray(mcpPayload.blockedMcpTools) ? mcpPayload.blockedMcpTools.length : 0
  const discoveryErrorCount = Array.isArray(mcpPayload.mcpDiscoveryErrors) ? mcpPayload.mcpDiscoveryErrors.length : 0
  const injected = Array.isArray(mcpPayload.injectedMcpTools) ? mcpPayload.injectedMcpTools : []
  const firstInjected = injected.find(
    (item): item is { callbackName?: unknown } => Boolean(item && typeof item === 'object'),
  )
  const firstCallbackName = typeof firstInjected?.callbackName === 'string' ? firstInjected.callbackName : '无'

  if (mcpToolCount === 0) {
    return `工具注入完成 | 本地: ${localToolCount} | MCP: 0 | 总计: ${totalToolCount} | 屏蔽: ${blockedCount} | 发现错误: ${discoveryErrorCount} | 首个MCP: 无`
  }

  return `工具注入完成 | 本地: ${localToolCount} | MCP: ${mcpToolCount} | 总计: ${totalToolCount} | 屏蔽: ${blockedCount} | 发现错误: ${discoveryErrorCount} | 首个MCP: ${firstCallbackName}`
}

function summarizeMcpConfirmResultPayload(payload: unknown): string | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }

  const confirmPayload = payload as {
    actionId?: unknown
    status?: unknown
    server?: unknown
    tool?: unknown
    result?: {
      error?: unknown
      exitCode?: unknown
    }
  }

  const actionId = typeof confirmPayload.actionId === 'string' ? confirmPayload.actionId : '-'
  const status = typeof confirmPayload.status === 'string' ? confirmPayload.status : 'UNKNOWN'
  const server = typeof confirmPayload.server === 'string' ? confirmPayload.server : 'unknown'
  const tool = typeof confirmPayload.tool === 'string' ? confirmPayload.tool : 'unknown'
  const result = confirmPayload.result && typeof confirmPayload.result === 'object' ? confirmPayload.result : {}
  const error = typeof result.error === 'string' ? result.error : ''
  const exitCode = typeof result.exitCode === 'number' && Number.isFinite(result.exitCode) ? result.exitCode : null

  if (status === 'REJECTED') {
    return `命令确认已拒绝 | actionId: ${actionId} | ${server}/${tool}`
  }
  if (status === 'CONFIRM_EXECUTION_FAILED') {
    return `命令确认执行失败 | actionId: ${actionId} | ${server}/${tool} | ${error || '未知错误'}`
  }
  if (status === 'CONFIRMED_EXECUTED') {
    return `命令确认已执行 | actionId: ${actionId} | ${server}/${tool} | exitCode: ${exitCode === null ? 'N/A' : String(exitCode)}`
  }
  return `命令确认结果 | actionId: ${actionId} | 状态: ${status} | ${server}/${tool}`
}

function summarizeToolCallPayload(payload: unknown): string | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }

  const toolPayload = payload as {
    tool?: unknown
    data?: Record<string, unknown>
  }
  const tool = typeof toolPayload.tool === 'string' && toolPayload.tool.trim().length > 0 ? toolPayload.tool : 'unknown'
  const data = toolPayload.data && typeof toolPayload.data === 'object' ? toolPayload.data : {}
  const keys = Object.keys(data)
  const url = typeof data.url === 'string' ? data.url : null
  const command = typeof data.command === 'string' ? data.command : null

  if (url) {
    return `工具调用 ${tool} | url: ${url}`
  }
  if (command) {
    return `工具调用 ${tool} | command: ${command} | 参数键: ${keys.join(', ') || '-'}`
  }
  return `工具调用 ${tool} | 参数键: ${keys.join(', ') || '-'}`
}

function summarizeToolResultPayload(payload: unknown): string | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }

  const toolPayload = payload as {
    tool?: unknown
    data?: Record<string, unknown>
  }
  const tool = typeof toolPayload.tool === 'string' && toolPayload.tool.trim().length > 0 ? toolPayload.tool : 'unknown'
  const data = toolPayload.data && typeof toolPayload.data === 'object' ? toolPayload.data : {}
  const ok = typeof data.ok === 'boolean' ? data.ok : null
  const status = typeof data.status === 'string' ? data.status : ''
  const actionId = typeof data.actionId === 'string' ? data.actionId : ''
  const error = typeof data.error === 'string' ? data.error : ''
  const hasContent = Array.isArray(data.content)

  if (status === 'PENDING_CONFIRMATION') {
    return `工具结果 ${tool} | 待确认执行 | actionId: ${actionId || '-'} `
  }
  if (status === 'BLOCKED_POLICY') {
    return `工具结果 ${tool} | 已拦截: ${error || '策略阻止'}`
  }

  if (ok === false) {
    return `工具结果 ${tool} | 失败: ${error || '未知错误'}`
  }
  if (hasContent) {
    return `工具结果 ${tool} | 成功 | content items: ${(data.content as unknown[]).length}`
  }
  return `工具结果 ${tool} | ${ok === true ? '成功' : '已返回'}`
}

function summarizeLoopPayload(payload: unknown, eventType: string): string | null {
  if (!payload || typeof payload !== 'object') {
    return null
  }

  const loopPayload = payload as {
    turnIndex?: unknown
    maxTurns?: unknown
    elapsedMs?: unknown
    reason?: unknown
  }
  const turn =
    typeof loopPayload.turnIndex === 'number' && Number.isFinite(loopPayload.turnIndex) ? loopPayload.turnIndex : null
  const maxTurns = typeof loopPayload.maxTurns === 'number' && Number.isFinite(loopPayload.maxTurns) ? loopPayload.maxTurns : null
  const elapsedMs =
    typeof loopPayload.elapsedMs === 'number' && Number.isFinite(loopPayload.elapsedMs) ? loopPayload.elapsedMs : null
  const reason =
    typeof loopPayload.reason === 'string' && loopPayload.reason.trim().length > 0 ? loopPayload.reason : 'unknown'
  const turnText = turn === null ? '轮次未知' : `第 ${turn} 轮`

  if (eventType === 'LOOP_TURN_STARTED') {
    if (maxTurns === null) {
      return `循环开始 | ${turnText}`
    }
    return `循环开始 | ${turnText} / 共 ${maxTurns} 轮`
  }

  if (eventType === 'LOOP_TURN_FINISHED') {
    if (elapsedMs === null) {
      return `循环结束 | ${turnText}`
    }
    return `循环结束 | ${turnText} | 耗时: ${elapsedMs}ms`
  }

  if (eventType === 'LOOP_TERMINATED') {
    return `循环终止 | ${turnText} | 原因: ${reason}`
  }

  return null
}

function extractToolActionInfo(eventType: string | undefined, payload: unknown): Pick<TimelineItem, 'actionId' | 'actionState'> {
  if (!payload || typeof payload !== 'object') {
    return {}
  }

  if (eventType === 'MCP_CONFIRM_RESULT') {
    const confirmPayload = payload as { actionId?: unknown; status?: unknown }
    const actionId = typeof confirmPayload.actionId === 'string' ? confirmPayload.actionId : undefined
    const status = typeof confirmPayload.status === 'string' ? confirmPayload.status : ''
    if (status === 'CONFIRMED_EXECUTED') {
      return { actionId, actionState: 'CONFIRMED_EXECUTED' }
    }
    if (status === 'CONFIRM_EXECUTION_FAILED') {
      return { actionId, actionState: 'CONFIRM_EXECUTION_FAILED' }
    }
    if (status === 'REJECTED') {
      return { actionId, actionState: 'REJECTED' }
    }
    return { actionId }
  }

  if (eventType !== 'TOOL_RESULT') {
    return {}
  }

  const toolPayload = payload as { data?: Record<string, unknown> }
  const data = toolPayload.data && typeof toolPayload.data === 'object' ? toolPayload.data : {}
  const status = typeof data.status === 'string' ? data.status : ''
  const actionId = typeof data.actionId === 'string' ? data.actionId : undefined

  if (status === 'PENDING_CONFIRMATION' && actionId) {
    return { actionId, actionState: 'PENDING_CONFIRMATION' }
  }
  if (status === 'BLOCKED_POLICY') {
    return { actionState: 'BLOCKED_POLICY' }
  }
  if (status.length > 0) {
    return { actionState: 'DONE' }
  }
  return {}
}

export function summarizePayload(payload: unknown, eventType?: string): string {
  if (eventType === 'RETRIEVAL_RESULT') {
    const retrievalSummary = summarizeRetrievalPayload(payload)
    if (retrievalSummary) {
      return retrievalSummary
    }
  }

  if (eventType === 'RAG_SYNC') {
    const ragSyncSummary = summarizeRagSyncPayload(payload)
    if (ragSyncSummary) {
      return ragSyncSummary
    }
  }

  if (eventType === 'MCP_TOOLS_BOUND') {
    const mcpToolsSummary = summarizeMcpToolsBoundPayload(payload)
    if (mcpToolsSummary) {
      return mcpToolsSummary
    }
  }

  if (eventType === 'MCP_CONFIRM_RESULT') {
    const confirmResultSummary = summarizeMcpConfirmResultPayload(payload)
    if (confirmResultSummary) {
      return confirmResultSummary
    }
  }

  if (eventType === 'TOOL_CALL') {
    const toolCallSummary = summarizeToolCallPayload(payload)
    if (toolCallSummary) {
      return toolCallSummary
    }
  }

  if (eventType === 'TOOL_RESULT') {
    const toolResultSummary = summarizeToolResultPayload(payload)
    if (toolResultSummary) {
      return toolResultSummary
    }
  }

  if (eventType === 'LOOP_TURN_STARTED' || eventType === 'LOOP_TURN_FINISHED' || eventType === 'LOOP_TERMINATED') {
    const loopSummary = summarizeLoopPayload(payload, eventType)
    if (loopSummary) {
      return loopSummary
    }
  }

  if (typeof payload === 'string') {
    return payload
  }

  if (payload && typeof payload === 'object' && 'content' in payload) {
    const content = (payload as { content?: unknown }).content
    if (typeof content === 'string') {
      return content
    }
  }

  return JSON.stringify(payload)
}

export function mapRunEventToTimelineItem(event: RunEventItem): TimelineItem {
  const parsed = safeParsePayload(event.payload)
  const actionInfo = extractToolActionInfo(event.type, parsed)

  return {
    id: event.id,
    seq: event.seq,
    createdAt: event.createdAt,
    type: event.type,
    summary: summarizePayload(parsed, event.type),
    rawPayload: typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2),
    source: 'history',
    ...actionInfo,
  }
}

export function createStreamTimelineItem(input: {
  id: string
  seq: number
  type: string
  createdAt: string
  payload: string
}): TimelineItem {
  const parsed = safeParsePayload(input.payload)
  const actionInfo = extractToolActionInfo(input.type, parsed)

  return {
    id: input.id,
    seq: input.seq,
    createdAt: input.createdAt,
    type: input.type,
    summary: summarizePayload(parsed, input.type),
    rawPayload: typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2),
    source: 'stream',
    ...actionInfo,
  }
}

export function mergeTimelineItems(streamItems: TimelineItem[], historyItems: TimelineItem[]): TimelineItem[] {
  const merged = new Map<string, TimelineItem>()

  for (const item of [...streamItems, ...historyItems]) {
    const key = item.source === 'history' ? item.id : `${item.type}-${item.seq}-${item.createdAt}`
    if (!merged.has(key) || item.source === 'history') {
      merged.set(key, item)
    }
  }

  return [...merged.values()].sort((a, b) => {
    const timeDiff = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
    if (timeDiff !== 0) {
      return timeDiff
    }
    return a.seq - b.seq
  })
}
