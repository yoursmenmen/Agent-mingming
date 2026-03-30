export interface StructuredMeta {
  toolName?: string
  source?: string
  generatedAt?: string
  [key: string]: unknown
}

export interface WeatherStructuredData {
  city?: string
  condition?: string
  tempC?: number
  feelsLikeC?: number
  humidity?: number
  windKph?: number
  [key: string]: unknown
}

export interface CalcResultStructuredData {
  expression?: string
  result?: unknown
  unit?: unknown | null
  [key: string]: unknown
}

export interface ToolErrorStructuredData {
  toolName?: string
  category?: string
  message?: string
  retryable?: boolean
  [key: string]: unknown
}

export interface WeatherStructuredPayload {
  type: 'weather'
  version: string
  data: WeatherStructuredData
  meta?: StructuredMeta
}

export interface CalcResultStructuredPayload {
  type: 'calc_result'
  version: string
  data: CalcResultStructuredData
  meta?: StructuredMeta
}

export interface ToolErrorStructuredPayload {
  type: 'tool_error'
  version: string
  data: ToolErrorStructuredData
  meta?: StructuredMeta
}

export interface UnknownStructuredPayload {
  type: 'unknown'
  version: string
  originalType: string
  data: Record<string, unknown>
  meta?: StructuredMeta
}

export type StructuredPayload =
  | WeatherStructuredPayload
  | CalcResultStructuredPayload
  | ToolErrorStructuredPayload
  | UnknownStructuredPayload
