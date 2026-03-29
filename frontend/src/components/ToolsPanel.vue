<script setup lang="ts">
import type { ToolInfo } from '../types/run'

defineProps<{
  tools: ToolInfo[]
}>()
</script>

<template>
  <section class="panel tools-panel">
    <div class="panel-header compact">
      <div>
        <p class="panel-kicker">Tools</p>
        <h2>可用工具</h2>
      </div>
      <p class="panel-tip">GET /api/tools</p>
    </div>

    <div class="tools-panel-body">
      <div v-if="!tools.length" class="empty-state">暂未发现可用工具。</div>

      <ul v-else class="tools-list">
        <li v-for="tool in tools" :key="tool.name" class="tools-item">
          <div class="tools-item-header">
            <strong>{{ tool.name }}</strong>
            <span>{{ tool.source }}</span>
          </div>
          <p>{{ tool.description }}</p>
        </li>
      </ul>
    </div>
  </section>
</template>
