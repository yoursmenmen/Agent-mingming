import type { ChatRequest } from '../types/chat'
import type { RunEventItem } from '../types/run'

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

export { DEV_TOKEN }
