import type { ChatRequest } from '../types/chat'
import type { McpServerInfo, RagDocuments, RagSourceInfo, RagSyncStatus, RunEventItem, ToolInfo } from '../types/run'

const DEV_TOKEN = 'dev-token-change-me'

export async function postChatStream(body: ChatRequest): Promise<Response> {
  return fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
    body: JSON.stringify(body),
  })
}

export async function fetchRunEvents(runId: string): Promise<RunEventItem[]> {
  const response = await fetch(`/api/runs/${runId}/events`, {
    headers: {
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
  })

  if (!response.ok) {
    throw new Error(`获取运行事件失败：${response.status}`)
  }

  return response.json() as Promise<RunEventItem[]>
}

export async function fetchSessionEvents(sessionId: string): Promise<RunEventItem[]> {
  const response = await fetch(`/api/sessions/${sessionId}/events`, {
    headers: {
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
  })

  if (!response.ok) {
    throw new Error(`获取会话事件失败：${response.status}`)
  }

  return response.json() as Promise<RunEventItem[]>
}

export async function fetchTools(): Promise<ToolInfo[]> {
  const response = await fetch('/api/tools', {
    headers: {
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
  })

  if (!response.ok) {
    throw new Error(`获取工具列表失败：${response.status}`)
  }

  const data = (await response.json()) as { tools?: ToolInfo[] }
  return data.tools ?? []
}

export async function fetchMcpServers(): Promise<McpServerInfo[]> {
  const response = await fetch('/api/mcp/servers', {
    headers: {
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
  })

  if (!response.ok) {
    throw new Error(`获取 MCP 服务失败：${response.status}`)
  }

  const payload = (await response.json()) as { servers?: Array<Partial<McpServerInfo>> }
  const servers = payload.servers ?? []
  return servers.map((server) => ({
    name: server.name ?? '',
    transport: server.transport ?? 'unknown',
    url: server.url ?? '',
    streaming: server.streaming ?? '',
    timeoutMs: Number(server.timeoutMs ?? 0),
    configuredEnabled: Boolean(server.configuredEnabled),
    effectiveEnabled: Boolean(server.effectiveEnabled),
    lastStatus: server.lastStatus,
    lastError: server.lastError,
    tools: Array.isArray(server.tools) ? server.tools : [],
  }))
}

export async function setMcpServerEnabled(server: string, enabled: boolean): Promise<void> {
  const response = await fetch('/api/mcp/servers/enabled', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
    body: JSON.stringify({ server, enabled }),
  })

  if (!response.ok) {
    throw new Error(`更新 MCP 开关失败：${response.status}`)
  }
}

export async function confirmMcpAction(actionId: string): Promise<{
  actionId: string
  status: string
  server?: string
  tool?: string
  result?: Record<string, unknown>
  ok?: boolean
  error?: string
}> {
  const response = await fetch(`/api/mcp/actions/${actionId}/confirm`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
  })

  if (!response.ok) {
    throw new Error(`确认 MCP 动作失败：${response.status}`)
  }

  return response.json() as Promise<{
    actionId: string
    status: string
    server?: string
    tool?: string
    result?: Record<string, unknown>
    ok?: boolean
    error?: string
  }>
}

export async function rejectMcpAction(actionId: string): Promise<void> {
  const response = await fetch(`/api/mcp/actions/${actionId}/reject`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
  })

  if (!response.ok) {
    throw new Error(`拒绝 MCP 动作失败：${response.status}`)
  }
}

export async function fetchRagSyncStatus(): Promise<RagSyncStatus> {
  const response = await fetch('/api/rag/sync/status', {
    headers: {
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
  })

  if (!response.ok) {
    throw new Error(`获取 RAG 同步状态失败：${response.status}`)
  }

  return response.json() as Promise<RagSyncStatus>
}

export async function triggerRagSync(): Promise<{ accepted: boolean; status: RagSyncStatus }> {
  const response = await fetch('/api/rag/sync/trigger', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
  })

  if (!response.ok) {
    throw new Error(`触发 RAG 同步失败：${response.status}`)
  }

  return response.json() as Promise<{ accepted: boolean; status: RagSyncStatus }>
}

export async function fetchRagSources(): Promise<RagSourceInfo[]> {
  const response = await fetch('/api/rag/sources', {
    headers: {
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
  })

  if (!response.ok) {
    throw new Error(`获取 RAG URL 列表失败：${response.status}`)
  }

  const payload = (await response.json()) as { sources?: RagSourceInfo[] }
  return payload.sources ?? []
}

export async function fetchRagDocuments(): Promise<RagDocuments> {
  const response = await fetch('/api/rag/documents', {
    headers: {
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
  })

  if (!response.ok) {
    throw new Error(`获取 RAG 文档列表失败：${response.status}`)
  }

  const payload = (await response.json()) as { localDocs?: string[]; urlDocs?: string[] }
  return {
    localDocs: payload.localDocs ?? [],
    urlDocs: payload.urlDocs ?? [],
  }
}

export { DEV_TOKEN }
