<script setup lang="ts">
import type { RunStatus } from '../types/run'

defineProps<{
  devToken: string
  statusLabel: string
  runStatus: RunStatus
}>()
</script>

<template>
  <header class="hero-card hero-card--compact">
    <div class="hero-main">
      <p class="eyebrow">Agent_mm 控制台</p>
      <h1>AI Agent 工作台</h1>
      <p class="hero-copy">把聊天放到主视区，状态和事件时间线按需展开查看。</p>
    </div>
    <div class="hero-meta hero-meta--compact">
      <div class="meta-pill meta-pill--compact">
        <span class="meta-label">开发 Token</span>
        <code>{{ devToken }}</code>
      </div>
      <div class="meta-pill status-pill meta-pill--compact" :data-status="runStatus">
        <span class="status-dot"></span>
        {{ statusLabel }}
      </div>
    </div>
  </header>
</template>
