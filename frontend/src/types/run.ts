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
  actionId?: string
  actionState?:
    | 'PENDING_CONFIRMATION'
    | 'BLOCKED_POLICY'
    | 'DONE'
    | 'CONFIRMED_EXECUTED'
    | 'CONFIRM_EXECUTION_FAILED'
    | 'REJECTED'
}

export interface PendingMcpAction {
  actionId: string
  tool: string
  summary: string
  createdAt: string
}

export type RunStatus = 'idle' | 'streaming' | 'done' | 'error'

export interface ToolInfo {
  name: string
  description: string
  source: string
}

export interface McpToolInfo {
  name: string
  description?: string
  inputSchema?: Record<string, unknown>
}

export interface McpServerInfo {
  name: string
  transport: string
  url: string
  streaming: string
  timeoutMs: number
  configuredEnabled: boolean
  effectiveEnabled: boolean
  lastStatus?: string
  lastError?: string
  tools: McpToolInfo[]
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
