import { mount } from '@vue/test-utils'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import ChatPanel from './ChatPanel.vue'
import type { ChatMessage } from '../types/chat'

function createMessages(): ChatMessage[] {
  return [
    {
      id: 'm1',
      role: 'assistant',
      content: 'hello',
      createdAt: new Date().toISOString(),
      status: 'done',
      structured: null,
    },
  ]
}

describe('ChatPanel keyboard send behavior', () => {
  beforeAll(() => {
    Object.defineProperty(window.HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: vi.fn(),
    })
  })

  it('emits send on Enter without Shift', async () => {
    const wrapper = mount(ChatPanel, {
      props: {
        messages: createMessages(),
        draft: 'test',
        runStatus: 'idle',
        errorMessage: '',
        formatTime: (value: string) => value,
      },
    })

    const textarea = wrapper.get('textarea')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: false })

    expect(wrapper.emitted('send')).toHaveLength(1)
  })

  it('does not emit send on Shift+Enter', async () => {
    const wrapper = mount(ChatPanel, {
      props: {
        messages: createMessages(),
        draft: 'test',
        runStatus: 'idle',
        errorMessage: '',
        formatTime: (value: string) => value,
      },
    })

    const textarea = wrapper.get('textarea')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: true })

    expect(wrapper.emitted('send')).toBeUndefined()
  })
})
