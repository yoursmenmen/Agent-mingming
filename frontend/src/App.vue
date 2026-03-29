<script setup lang="ts">
import { computed, ref } from 'vue'
import ChatPanel from './components/ChatPanel.vue'
import HeroHeader from './components/HeroHeader.vue'
import RunStatusPanel from './components/RunStatusPanel.vue'
import TimelinePanel from './components/TimelinePanel.vue'
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
  statusLabel,
  errorMessage,
  isRefreshing,
  sendMessage,
  refreshRunEvents,
  formatTime,
} = useChatConsole()

const isSidebarOpen = ref(false)
const expandedSidebarPanel = ref<'status' | 'timeline'>('status')

const sidebarTitle = computed(() => (expandedSidebarPanel.value === 'status' ? '运行状态' : '事件时间线'))

function toggleSidebar() {
  isSidebarOpen.value = !isSidebarOpen.value
}

function openSidebarPanel(panel: 'status' | 'timeline') {
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
        :format-time="formatTime"
        @send="sendMessage"
      />

      <aside class="sidebar" :class="{ 'sidebar--open': isSidebarOpen }">
        <div class="sidebar-rail" role="tablist" aria-label="侧栏面板切换">
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
            class="sidebar-tab"
            type="button"
            role="tab"
            :aria-selected="isSidebarOpen && expandedSidebarPanel === 'status'"
            :class="{ 'sidebar-tab--active': isSidebarOpen && expandedSidebarPanel === 'status' }"
            @click="openSidebarPanel('status')"
          >
            <span class="sidebar-icon">◎</span>
            <span class="sidebar-text">状态</span>
          </button>
          <button
            class="sidebar-tab"
            type="button"
            role="tab"
            :aria-selected="isSidebarOpen && expandedSidebarPanel === 'timeline'"
            :class="{ 'sidebar-tab--active': isSidebarOpen && expandedSidebarPanel === 'timeline' }"
            @click="openSidebarPanel('timeline')"
          >
            <span class="sidebar-icon">≋</span>
            <span class="sidebar-text">时间线</span>
          </button>
        </div>

        <div v-if="isSidebarOpen" class="sidebar-content">
          <div class="sidebar-panel-header">
            <div>
              <p class="panel-kicker">Inspector</p>
              <h2>{{ sidebarTitle }}</h2>
            </div>
            <button class="ghost-button sidebar-close" type="button" @click="toggleSidebar">关闭</button>
          </div>

          <RunStatusPanel
            v-show="expandedSidebarPanel === 'status'"
            :status-label="statusLabel"
            :run-id="runId"
            :timeline-count="timelineCount"
            :is-refreshing="isRefreshing"
            @refresh="refreshRunEvents"
          />
          <TimelinePanel
            v-show="expandedSidebarPanel === 'timeline'"
            :session-id="sessionId"
            :run-id="runId"
            :timeline-items="timelineItems"
            :format-time="formatTime"
          />
        </div>
      </aside>
    </main>
  </div>
</template>
