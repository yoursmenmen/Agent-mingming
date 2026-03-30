import type {
  CalcResultStructuredPayload,
  StructuredMeta,
  StructuredPayload,
  ToolErrorStructuredPayload,
  UnknownStructuredPayload,
  WeatherStructuredPayload,
} from '../types/structured'

type StructuredEnvelopeCandidate = {
  type: string
  version: string
  data: Record<string, unknown>
  meta?: StructuredMeta
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isStructuredEnvelope(value: unknown): value is StructuredEnvelopeCandidate {
  if (!isRecord(value)) {
    return false
  }

  if (typeof value.type !== 'string' || value.type.trim().length === 0) {
    return false
  }

  if (typeof value.version !== 'string' || value.version.trim().length === 0) {
    return false
  }

  if (!isRecord(value.data)) {
    return false
  }

  if (value.meta !== undefined && !isRecord(value.meta)) {
    return false
  }

  return true
}

export function isWeatherStructuredPayload(payload: StructuredPayload | null): payload is WeatherStructuredPayload {
  return payload?.type === 'weather'
}

export function isCalcResultStructuredPayload(payload: StructuredPayload | null): payload is CalcResultStructuredPayload {
  return payload?.type === 'calc_result'
}

export function isToolErrorStructuredPayload(payload: StructuredPayload | null): payload is ToolErrorStructuredPayload {
  return payload?.type === 'tool_error'
}

export function isUnknownStructuredPayload(payload: StructuredPayload | null): payload is UnknownStructuredPayload {
  return payload?.type === 'unknown'
}

export function parseStructuredPayload(input: unknown): StructuredPayload | null {
  if (!isStructuredEnvelope(input)) {
    return null
  }

  if (input.version !== 'v1') {
    return null
  }

  const base = {
    version: input.version,
    data: input.data,
    meta: input.meta,
  }

  switch (input.type) {
    case 'weather':
      return {
        type: 'weather',
        ...base,
      }
    case 'calc_result':
      return {
        type: 'calc_result',
        ...base,
      }
    case 'tool_error':
      return {
        type: 'tool_error',
        ...base,
      }
    default:
      return {
        type: 'unknown',
        version: input.version,
        originalType: input.type,
        data: input.data,
        meta: input.meta,
      }
  }

}
