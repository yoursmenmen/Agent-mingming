import { describe, expect, it } from 'vitest'
import type { TimelineItem } from '../types/run'
import { aggregateLoopStatusFromTimeline } from './useChatConsole'

function buildTimelineItem(seq: number, type: string, payload: Record<string, unknown>): TimelineItem {
  return {
    id: `evt-${seq}`,
    seq,
    createdAt: `2026-04-07T10:00:0${seq}Z`,
    type,
    summary: type,
    rawPayload: JSON.stringify(payload),
    source: 'history',
  }
}

describe('aggregateLoopStatusFromTimeline', () => {
  it('aggregates current turn and termination reason from loop events', () => {
    const status = aggregateLoopStatusFromTimeline([
      buildTimelineItem(1, 'LOOP_TURN_STARTED', { turnIndex: 1, maxTurns: 5 }),
      buildTimelineItem(2, 'LOOP_TURN_FINISHED', { turnIndex: 1, elapsedMs: 420 }),
      buildTimelineItem(3, 'LOOP_TURN_STARTED', { turnIndex: 2, maxTurns: 5 }),
      buildTimelineItem(4, 'LOOP_TERMINATED', { turnIndex: 2, reason: 'MAX_TURNS_REACHED' }),
    ])

    expect(status).not.toBeNull()
    expect(status?.currentTurn).toBe(2)
    expect(status?.maxTurns).toBe(5)
    expect(status?.elapsedMs).toBe(420)
    expect(status?.terminationReason).toBe('MAX_TURNS_REACHED')
    expect(status?.active).toBe(false)
  })
})
