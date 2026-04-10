<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  toolCallId: string
  toolName: string
  reason: string
  args: Record<string, unknown>
  runId: string
  onConfirm: (toolCallId: string, approved: boolean) => void
}>()

const pending = ref(true)
const decision = ref<'approved' | 'skipped' | null>(null)

function handleApprove() {
  pending.value = false
  decision.value = 'approved'
  props.onConfirm(props.toolCallId, true)
}

function handleSkip() {
  pending.value = false
  decision.value = 'skipped'
  props.onConfirm(props.toolCallId, false)
}
</script>

<template>
  <div class="tool-confirm-card" :class="{ resolved: !pending }">
    <div class="card-header">
      <span class="icon">🔧</span>
      <span class="tool-name">{{ toolName }}</span>
      <span v-if="!pending" class="badge" :class="decision">
        {{ decision === 'approved' ? '已允许' : '已跳过' }}
      </span>
    </div>
    <p class="reason">{{ reason }}</p>
    <details class="args-detail">
      <summary>查看参数</summary>
      <pre>{{ JSON.stringify(args, null, 2) }}</pre>
    </details>
    <div v-if="pending" class="actions">
      <button class="btn-approve" @click="handleApprove">允许执行</button>
      <button class="btn-skip" @click="handleSkip">跳过</button>
    </div>
  </div>
</template>

<style scoped>
.tool-confirm-card {
  border: 1px solid #f59e0b;
  border-radius: 8px;
  padding: 12px 16px;
  margin: 8px 0;
  background: #fffbeb;
}
.tool-confirm-card.resolved {
  border-color: #d1d5db;
  background: #f9fafb;
  opacity: 0.8;
}
.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  margin-bottom: 8px;
}
.tool-name { font-family: monospace; }
.badge {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 12px;
  font-weight: normal;
}
.badge.approved { background: #d1fae5; color: #065f46; }
.badge.skipped { background: #f3f4f6; color: #6b7280; }
.reason { margin: 0 0 8px; color: #374151; font-size: 14px; }
.args-detail summary { cursor: pointer; font-size: 13px; color: #6b7280; }
.args-detail pre {
  background: #f3f4f6;
  padding: 8px;
  border-radius: 4px;
  font-size: 12px;
  overflow-x: auto;
  margin-top: 4px;
}
.actions { display: flex; gap: 8px; margin-top: 10px; }
.btn-approve {
  background: #10b981;
  color: white;
  border: none;
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
  font-size: 14px;
}
.btn-approve:hover { background: #059669; }
.btn-skip {
  background: white;
  color: #374151;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
  font-size: 14px;
}
.btn-skip:hover { background: #f9fafb; }
</style>
