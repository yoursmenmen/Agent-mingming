import type { StructuredPayload } from './structured'

export type ChatRole = 'user' | 'assistant'

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  createdAt: string
  status?: 'streaming' | 'done' | 'error'
  structured?: StructuredPayload | null
}

export interface ChatRequest {
  message: string
  sessionId?: string
}

export interface StreamRunEvent {
  sessionId: string
  runId: string
}

export interface StreamMessageEvent {
  content: string
}

export interface StreamErrorEvent {
  message: string
}

export type StreamEventName = 'run' | 'event' | 'error'
