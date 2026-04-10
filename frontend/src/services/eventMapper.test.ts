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

  it('builds rag sync summary for started and completed phases', () => {
    const started = mapRunEventToTimelineItem({
      id: 'evt-sync-1',
      runId: 'run-1',
      seq: 5,
      createdAt: '2026-04-01T10:00:04Z',
      type: 'RAG_SYNC',
      payload: JSON.stringify({
        phase: 'started',
        trigger: 'manual',
      }),
    })

    const completed = mapRunEventToTimelineItem({
      id: 'evt-sync-2',
      runId: 'run-1',
      seq: 6,
      createdAt: '2026-04-01T10:00:05Z',
      type: 'RAG_SYNC',
      payload: JSON.stringify({
        phase: 'completed',
        trigger: 'manual',
        stats: {
          inserted: 3,
          updated: 2,
          softDeleted: 1,
          unchanged: 4,
        },
      }),
    })

    expect(started.summary).toContain('RAG 同步开始')
    expect(started.summary).toContain('触发: manual')
    expect(completed.summary).toContain('RAG 同步完成')
    expect(completed.summary).toContain('新增: 3')
    expect(completed.summary).toContain('更新: 2')
  })

  it('builds rag sync failed summary with error', () => {
    const failed = mapRunEventToTimelineItem({
      id: 'evt-sync-3',
      runId: 'run-1',
      seq: 7,
      createdAt: '2026-04-01T10:00:06Z',
      type: 'RAG_SYNC',
      payload: JSON.stringify({
        phase: 'failed',
        trigger: 'bootstrap',
        error: 'network timeout',
      }),
    })

    expect(failed.summary).toContain('RAG 同步失败')
    expect(failed.summary).toContain('触发: bootstrap')
    expect(failed.summary).toContain('network timeout')
  })

  it('builds mcp confirm result summary', () => {
    const item = mapRunEventToTimelineItem({
      id: 'evt-mcp-confirm-1',
      runId: 'run-1',
      seq: 8,
      createdAt: '2026-04-03T10:00:07Z',
      type: 'MCP_CONFIRM_RESULT',
      payload: JSON.stringify({
        actionId: 'action-1',
        status: 'CONFIRMED_EXECUTED',
        server: 'local-ops',
        tool: 'run_local_command',
        result: {
          exitCode: 0,
        },
      }),
    })

    expect(item.summary).toContain('命令确认已执行')
    expect(item.summary).toContain('action-1')
    expect(item.summary).toContain('local-ops/run_local_command')
    expect(item.summary).toContain('exitCode: 0')
    expect(item.actionState).toBe('CONFIRMED_EXECUTED')
  })
})
