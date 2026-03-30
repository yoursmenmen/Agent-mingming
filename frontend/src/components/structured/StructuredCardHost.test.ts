import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StructuredCardHost from './StructuredCardHost.vue'

describe('StructuredCardHost', () => {
  it('renders weather card for assistant messages', () => {
    const wrapper = mount(StructuredCardHost, {
      props: {
        role: 'assistant',
        structured: {
          type: 'weather',
          version: 'v1',
          data: {
            city: 'Shanghai',
            condition: 'Cloudy',
            tempC: 21,
          },
        },
      },
    })

    expect(wrapper.find('[data-testid="weather-card"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="unknown-structured-card"]').exists()).toBe(false)
  })

  it('does not render cards for user messages', () => {
    const wrapper = mount(StructuredCardHost, {
      props: {
        role: 'user',
        structured: {
          type: 'weather',
          version: 'v1',
          data: {
            city: 'Shanghai',
          },
        },
      },
    })

    expect(wrapper.find('[data-testid$="-card"]').exists()).toBe(false)
  })

  it('falls back to unknown card for parsed unknown payload', () => {
    const wrapper = mount(StructuredCardHost, {
      props: {
        role: 'assistant',
        structured: {
          type: 'unknown',
          version: 'v1',
          originalType: 'stock_quote',
          data: {
            symbol: 'AAPL',
          },
        },
      },
    })

    expect(wrapper.find('[data-testid="unknown-structured-card"]').exists()).toBe(true)
  })

  it('preserves original unknown type for already-parsed payload', () => {
    const wrapper = mount(StructuredCardHost, {
      props: {
        role: 'assistant',
        structured: {
          type: 'unknown',
          version: 'v1',
          originalType: 'stock_quote',
          data: {
            symbol: 'AAPL',
          },
        },
      },
    })

    expect(wrapper.find('[data-testid="unknown-structured-card"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('stock_quote')
  })

  it('renders calc result card for calc_result payload', () => {
    const wrapper = mount(StructuredCardHost, {
      props: {
        role: 'assistant',
        structured: {
          type: 'calc_result',
          version: 'v1',
          data: {
            expression: '1 + 2',
            result: 3,
            unit: 'number',
          },
        },
      },
    })

    expect(wrapper.find('[data-testid="calc-result-card"]').exists()).toBe(true)
  })

  it('renders tool error card for tool_error payload', () => {
    const wrapper = mount(StructuredCardHost, {
      props: {
        role: 'assistant',
        structured: {
          type: 'tool_error',
          version: 'v1',
          data: {
            toolName: 'get_weather',
            category: 'UPSTREAM_TIMEOUT',
            message: 'timeout',
            retryable: true,
          },
        },
      },
    })

    expect(wrapper.find('[data-testid="tool-error-card"]').exists()).toBe(true)
  })

  it('renders nothing for malformed structured payload', () => {
    const wrapper = mount(StructuredCardHost, {
      props: {
        role: 'assistant',
        structured: ({
          foo: 'bar',
        } as unknown) as never,
      },
    })

    expect(wrapper.find('[data-testid="structured-card-host"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="unknown-structured-card"]').exists()).toBe(false)
  })

  it('renders nothing when envelope misses required fields', () => {
    const wrapper = mount(StructuredCardHost, {
      props: {
        role: 'assistant',
        structured: ({
          type: 'stock_quote',
          version: 'v1',
        } as unknown) as never,
      },
    })

    expect(wrapper.find('[data-testid="structured-card-host"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="unknown-structured-card"]').exists()).toBe(false)
  })
})
