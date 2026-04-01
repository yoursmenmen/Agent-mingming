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
        strategy: 'hybrid',
        vectorHitCount: 2,
        bm25HitCount: 3,
        finalHitCount: 2,
        hits: [
          { docPath: 'docs/rag-fixtures/release-notes.md', source: 'vector' },
          { docPath: 'docs/rag-fixtures/architecture-long.md' },
        ],
      }),
    })

    expect(item.summary).toContain('策略: hybrid')
    expect(item.summary).toContain('向量: 2')
    expect(item.summary).toContain('BM25: 3')
    expect(item.summary).toContain('最终: 2')
    expect(item.summary).toContain('首条来源: vector')
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
        strategy: 'hybrid',
        vectorHitCount: 0,
        bm25HitCount: 0,
        finalHitCount: 0,
        hits: [],
      }),
    })

    expect(item.summary).toContain('未命中')
    expect(item.summary).toContain('策略: hybrid')
    expect(item.summary).toContain('向量: 0')
    expect(item.summary).toContain('BM25: 0')
    expect(item.summary).toContain('最终: 0')
    expect(item.summary).toContain('不存在的查询词')
    expect(item.summary).toContain('N/A')
    expect(item.summary).toContain('首条来源: N/A')
  })

  it('falls back to legacy payload fields when strategy and counts are missing', () => {
    const item = mapRunEventToTimelineItem({
      id: 'evt-3',
      runId: 'run-1',
      seq: 4,
      createdAt: '2026-03-30T10:00:03Z',
      type: 'RETRIEVAL_RESULT',
      payload: JSON.stringify({
        query: '旧格式查询',
        hitCount: 1,
        hits: [{ docPath: 'docs/legacy.md' }],
      }),
    })

    expect(item.summary).toContain('命中 1 条')
    expect(item.summary).toContain('策略: unknown')
    expect(item.summary).toContain('向量: 1')
    expect(item.summary).toContain('BM25: 1')
    expect(item.summary).toContain('最终: 1')
    expect(item.summary).toContain('首条来源: N/A')
    expect(item.summary).toContain('docs/legacy.md')
  })
})
