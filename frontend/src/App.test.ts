import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from './App.vue'

vi.mock('./services/api', () => ({
  DEV_TOKEN: 'dev-token-change-me',
}))

vi.mock('./composables/useChatConsole', () => ({
  useChatConsole: () => ({
    messages: [],
    draft: '',
    sessionId: null,
    runId: 'run-1',
    runStatus: 'idle',
    timelineItems: [],
    timelineCount: 0,
    availableTools: [],
    mcpServers: [],
    ragSyncStatus: null,
    ragSources: [],
    ragDocuments: { localDocs: [], urlDocs: [] },
    statusLabel: '待命中',
    errorMessage: '',
    isRefreshing: false,
    isRagRefreshing: false,
    isRagTriggering: false,
    isMcpRefreshing: false,
    mcpUpdatingServers: [],
    sendMessage: vi.fn(),
    refreshRunEvents: vi.fn(),
    refreshTools: vi.fn(),
    refreshMcpServers: vi.fn(),
    toggleMcpServer: vi.fn(),
    refreshRagStatus: vi.fn(),
    triggerRagSyncNow: vi.fn(),
    formatTime: (value: string) => value,
  }),
}))

describe('App inspector layout', () => {
  it('renders the new inspector shell structure when sidebar opens', async () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          HeroHeader: { template: '<div class="hero-header-stub" />' },
          ChatPanel: { template: '<div class="chat-panel-stub" />' },
          RunStatusPanel: { template: '<div class="status-pane-stub">status</div>' },
          RagPanel: { template: '<div class="rag-pane-stub">rag</div>' },
          TimelinePanel: { template: '<div class="timeline-pane-stub">timeline</div>' },
          ToolsPanel: { template: '<div class="tools-pane-stub">tools</div>' },
        },
      },
    })

    await wrapper.get('button[aria-label="展开侧栏"]').trigger('click')

    expect(wrapper.find('.inspector-shell').exists()).toBe(true)
    expect(wrapper.find('.inspector-nav').exists()).toBe(true)
    expect(wrapper.find('.inspector-main').exists()).toBe(true)
    expect(wrapper.find('.inspector-pane-host').exists()).toBe(true)
  })

  it('mounts only the active inspector pane', async () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          HeroHeader: { template: '<div class="hero-header-stub" />' },
          ChatPanel: { template: '<div class="chat-panel-stub" />' },
          RunStatusPanel: { template: '<div class="status-pane-stub">status</div>' },
          RagPanel: { template: '<div class="rag-pane-stub">rag</div>' },
          TimelinePanel: { template: '<div class="timeline-pane-stub">timeline</div>' },
          ToolsPanel: { template: '<div class="tools-pane-stub">tools</div>' },
        },
      },
    })

    await wrapper.get('button[aria-label="展开侧栏"]').trigger('click')

    const tabs = wrapper.findAll('.sidebar-tab')
    await tabs[1].trigger('click')

    expect(wrapper.find('.rag-pane-stub').exists()).toBe(true)
    expect(wrapper.find('.status-pane-stub').exists()).toBe(false)
    expect(wrapper.find('.timeline-pane-stub').exists()).toBe(false)
    expect(wrapper.find('.tools-pane-stub').exists()).toBe(false)

    await tabs[3].trigger('click')

    expect(wrapper.find('.tools-pane-stub').exists()).toBe(true)
    expect(wrapper.find('.rag-pane-stub').exists()).toBe(false)
  })
})
