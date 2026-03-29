<script setup lang="ts">
defineProps<{
  statusLabel: string
  runId: string
  timelineCount: number
  isRefreshing: boolean
}>()

const emit = defineEmits<{
  refresh: []
}>()
</script>

<template>
  <section class="panel status-panel">
    <div class="panel-header compact">
      <div>
        <p class="panel-kicker">Run</p>
        <h2>运行状态</h2>
      </div>
      <button class="ghost-button" type="button" @click="emit('refresh')" :disabled="isRefreshing">
        {{ isRefreshing ? '刷新中…' : '刷新事件' }}
      </button>
    </div>

    <dl class="status-grid">
      <div>
        <dt>当前状态</dt>
        <dd>{{ statusLabel }}</dd>
      </div>
      <div>
        <dt>Run ID</dt>
        <dd class="run-id">{{ runId }}</dd>
      </div>
      <div>
        <dt>时间线条数</dt>
        <dd>{{ timelineCount }}</dd>
      </div>
      <div>
        <dt>视图模式</dt>
        <dd>聊天 + 时间线</dd>
      </div>
    </dl>

  </section>
</template>
