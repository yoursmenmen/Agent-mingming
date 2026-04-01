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
