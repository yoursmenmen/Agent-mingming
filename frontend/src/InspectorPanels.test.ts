import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RagPanel from './components/RagPanel.vue'
import RunStatusPanel from './components/RunStatusPanel.vue'
import TimelinePanel from './components/TimelinePanel.vue'
import ToolsPanel from './components/ToolsPanel.vue'

describe('Inspector pane layout', () => {
  it('renders a dedicated body container for status, timeline and tools panes', () => {
    const status = mount(RunStatusPanel, {
      props: {
        statusLabel: '运行中',
        runId: 'run-1',
        timelineCount: 3,
        isRefreshing: false,
      },
    })
    const timeline = mount(TimelinePanel, {
      props: {
        sessionId: 'session-1',
        runId: 'run-1',
        timelineItems: [],
        formatTime: (value: string) => value,
      },
    })
    const tools = mount(ToolsPanel, {
      props: {
        tools: [],
        mcpServers: [],
        isMcpRefreshing: false,
        mcpUpdatingServers: [],
        onRefreshTools: () => {},
        onRefreshMcpServers: () => {},
        onToggleMcpServer: () => {},
      },
    })

    expect(status.find('.status-panel-body').exists()).toBe(true)
    expect(timeline.find('.timeline-panel-body').exists()).toBe(true)
    expect(tools.find('.tools-panel-body').exists()).toBe(true)
  })

  it('keeps rag detail sections inside the rag scroll body with per-section toggles', () => {
    const wrapper = mount(RagPanel, {
      props: {
        ragSyncStatus: {
          state: 'IDLE',
          lastStartAt: null,
          lastSuccessAt: null,
          chunkCount: 2,
          embeddingCount: 2,
          lastError: '',
          sourceStats: {
            localDocs: 1,
            urlSources: 1,
          },
        },
        ragSources: [
          {
            name: 'Docs',
            url: 'https://example.com/docs',
            enabled: true,
            lastStatus: 'SUCCESS',
            lastCheckedAt: null,
            lastError: '',
          },
        ],
        ragDocuments: {
          localDocs: ['docs/project-overview.md'],
          urlDocs: ['https://example.com/docs/page'],
        },
        isRagRefreshing: false,
        isRagTriggering: false,
      },
    })

    expect(wrapper.find('.rag-panel-body').exists()).toBe(true)
    expect(wrapper.find('.rag-panel-body .rag-section').exists()).toBe(true)
    expect(wrapper.findAll('.rag-section-toggle')).toHaveLength(3)
    expect(wrapper.find('.rag-source-preview').exists()).toBe(false)
  })

  it('renders compact rag source status metadata with indicator dot', () => {
    const wrapper = mount(RagPanel, {
      props: {
        ragSyncStatus: null,
        ragSources: [
          {
            name: 'Docs',
            url: 'https://example.com/docs',
            enabled: true,
            lastStatus: 'NOT_MODIFIED',
            lastCheckedAt: null,
            lastError: '',
          },
          {
            name: 'MDN',
            url: 'https://developer.mozilla.org',
            enabled: false,
            lastStatus: 'DISABLED',
            lastCheckedAt: null,
            lastError: '',
          },
        ],
        ragDocuments: {
          localDocs: [],
          urlDocs: [],
        },
        isRagRefreshing: false,
        isRagTriggering: false,
      },
    })

    const chips = wrapper.findAll('.rag-source-meta')
    expect(chips).toHaveLength(2)
    expect(chips[0].text()).toContain('已启用')
    expect(chips[0].text()).toContain('NOT_MODIFIED')
    expect(chips[0].classes()).toContain('rag-source-meta--enabled')
    expect(chips[1].text()).toContain('未启用')
    expect(chips[1].classes()).toContain('rag-source-meta--disabled')
  })
})
