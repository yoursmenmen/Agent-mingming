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
  state: 'PENDING_CONFIRMATION' | 'PROCESSING'
}

export interface OnboardingPlanCard {
  planId: string
  repoUrl: string
  source: string
  owner: string
  repo: string
  serverName: string
  preferredTransport: string
  cloneDir: string
  startupCommand: string
  installCommands: string[]
  requiredEnv: string[]
  missingRequiredEnv: string[]
  warnings: string[]
  readyToApply: boolean
  createdAt: string
  state: 'READY' | 'APPLYING' | 'DISMISSED' | 'APPLIED'
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

export interface RunEventMetrics {
  windowHours: number
  from: string
  to: string
  tool_call_total: number
  tool_result_total: number
  tool_error_total: number
  confirm_total: number
  confirm_success_total: number
  confirm_failed_total: number
  confirm_rejected_total: number
  contract_warning_total: number
}

export interface ToolConfirmPayload {
  type: 'TOOL_CONFIRM_REQUIRED'
  toolCallId: string
  toolName: string
  args: Record<string, unknown>
  reason: string
}

export interface PendingToolConfirm {
  toolCallId: string
  toolName: string
  args: Record<string, unknown>
  reason: string
  runId: string
}
