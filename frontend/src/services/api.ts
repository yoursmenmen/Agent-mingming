import type { ChatRequest } from '../types/chat'
import type { RagDocuments, RagSourceInfo, RagSyncStatus, RunEventItem, ToolInfo } from '../types/run'

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
