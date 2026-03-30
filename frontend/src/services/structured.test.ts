import { describe, expect, it } from 'vitest'
import { isWeatherStructuredPayload, parseStructuredPayload } from './structured'

describe('parseStructuredPayload', () => {
  it('parses a valid weather envelope', () => {
    const parsed = parseStructuredPayload({
      type: 'weather',
      version: 'v1',
      data: {
        city: 'Shanghai',
        condition: 'Cloudy',
        tempC: 21.5,
        feelsLikeC: 22,
        humidity: 68,
        windKph: 10,
      },
      meta: {
        toolName: 'get_weather',
      },
    })

    expect(parsed).not.toBeNull()
    expect(isWeatherStructuredPayload(parsed)).toBe(true)
    if (!parsed || !isWeatherStructuredPayload(parsed)) {
      return
    }

    expect(parsed.type).toBe('weather')
    expect(parsed.data.city).toBe('Shanghai')
    expect(parsed.data.tempC).toBe(21.5)
  })

  it('falls back to unknown payload for unsupported type', () => {
    const parsed = parseStructuredPayload({
      type: 'stock_quote',
      version: 'v1',
      data: {
        symbol: 'AAPL',
      },
    })

    expect(parsed).not.toBeNull()
    expect(parsed?.type).toBe('unknown')
    if (!parsed || parsed.type !== 'unknown') {
      return
    }

    expect(parsed.originalType).toBe('stock_quote')
    expect(parsed.data).toEqual({ symbol: 'AAPL' })
  })

  it('returns null for an invalid envelope', () => {
    const parsed = parseStructuredPayload({
      type: 'weather',
      data: {
        city: 'Shanghai',
      },
    })

    expect(parsed).toBeNull()
  })

  it('returns null when version is not v1', () => {
    const parsed = parseStructuredPayload({
      type: 'weather',
      version: 'v2',
      data: {
        city: 'Shanghai',
      },
    })

    expect(parsed).toBeNull()
  })

  it('preserves known type even when data is partial', () => {
    const parsed = parseStructuredPayload({
      type: 'tool_error',
      version: 'v1',
      data: {
        message: 'something failed',
      },
    })

    expect(parsed).not.toBeNull()
    expect(parsed?.type).toBe('tool_error')
    if (!parsed || parsed.type !== 'tool_error') {
      return
    }

    expect(parsed.data).toEqual({ message: 'something failed' })
  })
})
