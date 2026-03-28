export interface RunEventItem {
  id: string
  runId: string
  seq: number
  createdAt: string
  type: string
  payload: string
}

export interface TimelineItem {
  id: string
  seq: number
  createdAt: string
  type: string
  summary: string
  rawPayload: string
  source: 'stream' | 'history'
}

export type RunStatus = 'idle' | 'streaming' | 'done' | 'error'
