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
  const hitCount =
    typeof retrievalPayload.hitCount === 'number' && Number.isFinite(retrievalPayload.hitCount)
      ? retrievalPayload.hitCount
      : hits.length

  if (hitCount > 0) {
    return `命中 ${hitCount} 条 | 查询: ${query} | 代表文档: ${representativeDocPath}`
  }

  return `未命中 | 查询: ${query} | 代表文档: ${representativeDocPath}`
}

export function summarizePayload(payload: unknown, eventType?: string): string {
  if (eventType === 'RETRIEVAL_RESULT') {
    const retrievalSummary = summarizeRetrievalPayload(payload)
    if (retrievalSummary) {
      return retrievalSummary
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
