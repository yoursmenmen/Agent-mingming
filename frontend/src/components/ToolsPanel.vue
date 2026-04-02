<script setup lang="ts">
import { computed, onMounted } from 'vue'
import type { McpServerInfo, ToolInfo } from '../types/run'

const props = withDefaults(defineProps<{
  tools: ToolInfo[]
  mcpServers?: McpServerInfo[]
  isMcpRefreshing?: boolean
  mcpUpdatingServers?: string[]
  onRefreshTools?: () => void
  onRefreshMcpServers?: () => void
  onToggleMcpServer?: (server: string, enabled: boolean) => void
}>(), {
  mcpServers: () => [],
  isMcpRefreshing: false,
  mcpUpdatingServers: () => [],
  onRefreshTools: () => {},
  onRefreshMcpServers: () => {},
  onToggleMcpServer: () => {},
})

const mcpServersSafe = computed(() => props.mcpServers ?? [])

onMounted(() => {
  props.onRefreshTools?.()
  props.onRefreshMcpServers?.()
})

function isUpdating(server: string): boolean {
  return (props.mcpUpdatingServers ?? []).includes(server)
}

function toggle(server: McpServerInfo) {
  props.onToggleMcpServer?.(server.name, !server.effectiveEnabled)
}
</script>

<template>
  <section class="panel tools-panel">
    <div class="panel-header compact">
      <div>
        <p class="panel-kicker">Tools</p>
        <h2>可用工具</h2>
      </div>
      <div class="tools-actions">
        <button class="ghost-button" type="button" @click="onRefreshTools">刷新本地</button>
        <button class="ghost-button" type="button" @click="onRefreshMcpServers">刷新 MCP</button>
      </div>
    </div>

    <div class="tools-panel-body pane-body">
      <section class="tools-section">
        <div class="tools-section-header">
          <h3>本地工具（{{ tools.length }}）</h3>
          <p class="panel-tip">GET /api/tools</p>
        </div>
        <div v-if="!tools.length" class="empty-state">暂未发现本地工具。</div>

        <ul v-else class="tools-list">
          <li v-for="tool in tools" :key="tool.name" class="tools-item">
            <div class="tools-item-header">
              <strong>{{ tool.name }}</strong>
              <span>{{ tool.source }}</span>
            </div>
            <p>{{ tool.description }}</p>
          </li>
        </ul>
      </section>

      <section class="tools-section">
        <div class="tools-section-header">
          <h3>MCP 服务（{{ mcpServersSafe.length }}）</h3>
          <p class="panel-tip">GET /api/mcp/servers</p>
        </div>
        <div v-if="isMcpRefreshing" class="empty-state">刷新 MCP 中…</div>
        <div v-else-if="!mcpServersSafe.length" class="empty-state">暂未发现 MCP 服务。</div>

        <ul v-else class="mcp-server-list">
          <li v-for="server in mcpServersSafe" :key="server.name" class="mcp-server-item">
            <div class="mcp-server-header">
              <div>
                <strong>{{ server.name }}</strong>
                <p class="mcp-server-url">{{ server.url }}</p>
              </div>
              <button
                class="ghost-button mcp-toggle"
                type="button"
                :disabled="isUpdating(server.name)"
                @click="toggle(server)"
              >
                {{ server.effectiveEnabled ? '关闭' : '开启' }}
              </button>
            </div>

            <p class="mcp-server-meta">
              <span>状态：{{ server.lastStatus ?? 'UNKNOWN' }}</span>
              <span>传输：{{ server.transport }}</span>
              <span>超时：{{ server.timeoutMs }}ms</span>
            </p>
            <p v-if="server.lastError" class="rag-error-inline">{{ server.lastError }}</p>

            <details class="mcp-tools-detail">
              <summary>查看工具（{{ server.tools?.length ?? 0 }}）</summary>
              <ul v-if="(server.tools?.length ?? 0) > 0" class="mcp-tools-list">
                <li v-for="tool in server.tools" :key="`${server.name}-${tool.name}`">
                  <strong>{{ tool.name }}</strong>
                  <p>{{ tool.description || '无描述' }}</p>
                </li>
              </ul>
              <p v-else class="empty-state">当前服务无工具或未连通。</p>
            </details>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>
