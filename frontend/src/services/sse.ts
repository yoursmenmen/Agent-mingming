import type { StreamEventName } from '../types/chat'

export interface ParsedSseEvent {
  event: StreamEventName
  data: string
}

function parseEventBlock(block: string): ParsedSseEvent | null {
  const lines = block.split(/\r?\n/)
  let eventName = 'event'
  const dataLines: string[] = []

  for (const line of lines) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
      continue
    }

    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim())
    }
  }

  if (!dataLines.length) {
    return null
  }

  if (eventName !== 'run' && eventName !== 'event' && eventName !== 'error') {
    return null
  }

  return {
    event: eventName,
    data: dataLines.join('\n'),
  }
}

export async function consumeSseStream(
  response: Response,
  onEvent: (event: ParsedSseEvent) => void,
): Promise<void> {
  if (!response.ok) {
    throw new Error(`聊天请求失败：${response.status}`)
  }

  if (!response.body) {
    throw new Error('未收到响应流')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()

    if (done) {
      break
    }

    buffer += decoder.decode(value, { stream: true })
    const chunks = buffer.split(/\r?\n\r?\n/)
    buffer = chunks.pop() ?? ''

    for (const chunk of chunks) {
      const parsed = parseEventBlock(chunk.trim())
      if (parsed) {
        onEvent(parsed)
      }
    }
  }

  if (buffer.trim()) {
    const parsed = parseEventBlock(buffer.trim())
    if (parsed) {
      onEvent(parsed)
    }
  }
}
