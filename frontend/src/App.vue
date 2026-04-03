<script setup lang="ts">
import { computed, markRaw, ref } from 'vue'
import type { Component } from 'vue'
import ChatPanel from './components/ChatPanel.vue'
import HeroHeader from './components/HeroHeader.vue'
import RagPanel from './components/RagPanel.vue'
import RunStatusPanel from './components/RunStatusPanel.vue'
import TimelinePanel from './components/TimelinePanel.vue'
import ToolsPanel from './components/ToolsPanel.vue'
import { useChatConsole } from './composables/useChatConsole'
import { DEV_TOKEN } from './services/api'

const {
  messages,
  draft,
  sessionId,
  runId,
  runStatus,
  timelineItems,
  timelineCount,
  availableTools,
  mcpServers,
  ragSyncStatus,
  ragSources,
  ragDocuments,
  runMetrics,
  statusLabel,
  errorMessage,
  isRefreshing,
  isRagRefreshing,
  isRagTriggering,
  isMcpRefreshing,
  mcpUpdatingServers,
  pendingMcpActions,
  handlingActionIds,
  sendMessage,
  refreshRunEvents,
  refreshTools,
  refreshMcpServers,
  toggleMcpServer,
  confirmPendingMcpAction,
  rejectPendingMcpAction,
  refreshRagStatus,
  triggerRagSyncNow,
  formatTime,
} = useChatConsole()

type InspectorPaneId = 'status' | 'rag' | 'timeline' | 'tools'

type InspectorPane = {
  id: InspectorPaneId
  label: string
  icon: string
  title: string
  component: Component
}

function readState<T>(value: T | { value: T }): T {
  if (typeof value === 'object' && value !== null && 'value' in value) {
    return value.value
  }
  return value
}

const inspectorPanes: InspectorPane[] = [
  { id: 'status', label: '状态', icon: '◉', title: '运行状态', component: markRaw(RunStatusPanel) },
  { id: 'rag', label: 'RAG', icon: '◈', title: 'RAG 同步', component: markRaw(RagPanel) },
  { id: 'timeline', label: '轨迹', icon: '☰', title: '事件时间线', component: markRaw(TimelinePanel) },
  { id: 'tools', label: '工具', icon: '⚙', title: '可用工具', component: markRaw(ToolsPanel) },
]

const isSidebarOpen = ref(false)
const expandedSidebarPanel = ref<InspectorPaneId>('status')

const activeInspectorPane = computed(() => {
  return inspectorPanes.find((pane) => pane.id === expandedSidebarPanel.value) ?? inspectorPanes[0]
})

const activeInspectorProps = computed(() => {
  if (activeInspectorPane.value.id === 'status') {
    return {
      statusLabel: readState(statusLabel),
      runId: readState(runId),
      timelineCount: readState(timelineCount),
      isRefreshing: readState(isRefreshing),
      runMetrics: readState(runMetrics),
      onRefresh: refreshRunEvents,
    }
  }

  if (activeInspectorPane.value.id === 'rag') {
    return {
      ragSyncStatus: readState(ragSyncStatus),
      ragSources: readState(ragSources),
      ragDocuments: readState(ragDocuments),
      isRagRefreshing: readState(isRagRefreshing),
      isRagTriggering: readState(isRagTriggering),
      onRefreshRag: refreshRagStatus,
      onTriggerRag: triggerRagSyncNow,
    }
  }

  if (activeInspectorPane.value.id === 'timeline') {
    return {
      sessionId: readState(sessionId),
      runId: readState(runId),
      timelineItems: readState(timelineItems),
      formatTime,
    }
  }

  return {
    tools: readState(availableTools),
    mcpServers: readState(mcpServers),
    isMcpRefreshing: readState(isMcpRefreshing),
    mcpUpdatingServers: readState(mcpUpdatingServers),
    onRefreshTools: refreshTools,
    onRefreshMcpServers: refreshMcpServers,
    onToggleMcpServer: toggleMcpServer,
  }
})

function toggleSidebar() {
  isSidebarOpen.value = !isSidebarOpen.value
}

function openSidebarPanel(panel: InspectorPaneId) {
  expandedSidebarPanel.value = panel
  isSidebarOpen.value = true
}
</script>

<template>
  <div class="app-shell" :class="{ 'app-shell--sidebar-open': isSidebarOpen }">
    <HeroHeader :dev-token="DEV_TOKEN" :status-label="statusLabel" :run-status="runStatus" />

    <main class="workspace" :class="{ 'workspace--sidebar-open': isSidebarOpen }">
      <ChatPanel
        v-model:draft="draft"
        :messages="messages"
        :run-status="runStatus"
        :error-message="errorMessage"
        :pending-mcp-actions="pendingMcpActions"
        :handling-action-ids="handlingActionIds"
        :format-time="formatTime"
        @confirm-pending-action="confirmPendingMcpAction"
        @reject-pending-action="rejectPendingMcpAction"
        @send="sendMessage"
      />

      <aside class="inspector-shell" :class="{ 'inspector-shell--open': isSidebarOpen }">
        <div class="inspector-nav" role="tablist" aria-label="侧栏面板切换">
          <button
            class="sidebar-toggle"
            type="button"
            :aria-expanded="isSidebarOpen"
            :aria-label="isSidebarOpen ? '收起侧栏' : '展开侧栏'"
            @click="toggleSidebar"
          >
            <span class="sidebar-icon">⌘</span>
            <span class="sidebar-text">{{ isSidebarOpen ? '收起' : '侧栏' }}</span>
          </button>

          <button
            v-for="pane in inspectorPanes"
            :key="pane.id"
            class="sidebar-tab"
            type="button"
            role="tab"
            :aria-selected="isSidebarOpen && expandedSidebarPanel === pane.id"
            :class="{ 'sidebar-tab--active': isSidebarOpen && expandedSidebarPanel === pane.id }"
            @click="openSidebarPanel(pane.id)"
          >
            <span class="sidebar-icon">{{ pane.icon }}</span>
            <span class="sidebar-text">{{ pane.label }}</span>
          </button>
        </div>

        <div v-if="isSidebarOpen" class="inspector-main">
          <div class="inspector-header">
            <div>
              <p class="panel-kicker">Inspector</p>
              <h2>{{ activeInspectorPane.title }}</h2>
            </div>
            <button class="ghost-button sidebar-close" type="button" @click="toggleSidebar">关闭</button>
          </div>

          <div class="inspector-pane-host">
            <component :is="activeInspectorPane.component" v-bind="activeInspectorProps" />
          </div>
        </div>
      </aside>
    </main>
  </div>
</template>
