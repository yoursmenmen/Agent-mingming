import { describe, expect, it } from 'vitest'
import { mapRunEventToTimelineItem } from './eventMapper'

describe('mapRunEventToTimelineItem', () => {
  it('builds retrieval summary with hit and representative doc path', () => {
    const item = mapRunEventToTimelineItem({
      id: 'evt-1',
      runId: 'run-1',
      seq: 2,
      createdAt: '2026-03-30T10:00:00Z',
      type: 'RETRIEVAL_RESULT',
      payload: JSON.stringify({
        query: '如何启动本地后端',
        hitCount: 2,
        hits: [
          { docPath: 'docs/rag-fixtures/release-notes.md' },
          { docPath: 'docs/rag-fixtures/architecture-long.md' },
        ],
      }),
    })

    expect(item.summary).toContain('命中')
    expect(item.summary).toContain('如何启动本地后端')
    expect(item.summary).toContain('docs/rag-fixtures/release-notes.md')
  })

  it('builds retrieval summary for no-hit result', () => {
    const item = mapRunEventToTimelineItem({
      id: 'evt-2',
      runId: 'run-1',
      seq: 3,
      createdAt: '2026-03-30T10:00:02Z',
      type: 'RETRIEVAL_RESULT',
      payload: JSON.stringify({
        query: '不存在的查询词',
        hitCount: 0,
        hits: [],
      }),
    })

    expect(item.summary).toContain('未命中')
    expect(item.summary).toContain('不存在的查询词')
    expect(item.summary).toContain('N/A')
  })
})
