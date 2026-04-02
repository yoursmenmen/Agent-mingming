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
  const firstCallbackName = typeof firstInjected?.callbackName === 'string' ? firstInjected.callbackName : 'N/A'

  return `工具注入完成 | 本地: ${localToolCount} | MCP: ${mcpToolCount} | 总计: ${totalToolCount} | 屏蔽: ${blockedCount} | 发现错误: ${discoveryErrorCount} | 首个MCP: ${firstCallbackName}`
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
  const error = typeof data.error === 'string' ? data.error : ''
  const hasContent = Array.isArray(data.content)

  if (ok === false) {
    return `工具结果 ${tool} | 失败: ${error || '未知错误'}`
  }
  if (hasContent) {
    return `工具结果 ${tool} | 成功 | content items: ${(data.content as unknown[]).length}`
  }
  return `工具结果 ${tool} | ${ok === true ? '成功' : '已返回'}`
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

  return {
    id: event.id,
    seq: event.seq,
    createdAt: event.createdAt,
    type: event.type,
    summary: summarizePayload(parsed, event.type),
    rawPayload: typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2),
    source: 'history',
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

  return {
    id: input.id,
    seq: input.seq,
    createdAt: input.createdAt,
    type: input.type,
    summary: summarizePayload(parsed, input.type),
    rawPayload: typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2),
    source: 'stream',
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
