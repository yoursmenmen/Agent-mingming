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

export interface ToolInfo {
  name: string
  description: string
  source: string
}

export interface RagSyncStatus {
  state: string
  lastStartAt: string | null
  lastSuccessAt: string | null
  lastError: string | null
  chunkCount: number
  embeddingCount: number
  sourceStats: {
    localDocs: number
    urlSources: number
  }
}

export interface RagSourceInfo {
  name: string
  url: string
  enabled: boolean
  lastStatus: string
  lastCheckedAt: string | null
  lastError: string | null
}

export interface RagDocuments {
  localDocs: string[]
  urlDocs: string[]
}
