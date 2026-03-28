export type ChatRole = 'user' | 'assistant'

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  createdAt: string
  status?: 'streaming' | 'done' | 'error'
}

export interface ChatRequest {
  message: string
}

export interface StreamRunEvent {
  runId: string
}

export interface StreamMessageEvent {
  content: string
}

export interface StreamErrorEvent {
  message: string
}

export type StreamEventName = 'run' | 'event' | 'error'
