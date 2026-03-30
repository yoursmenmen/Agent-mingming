<script setup lang="ts">
import StructuredCardShell from './StructuredCardShell.vue'
import type { ToolErrorStructuredPayload } from '../../types/structured'

defineProps<{
  payload: ToolErrorStructuredPayload
}>()
</script>

<template>
  <StructuredCardShell title="工具异常" subtitle="tool_error.v1" data-testid="tool-error-card">
    <div class="tool-error">
      <div class="tool-error-head">
        <span class="retry-chip" :class="payload.data.retryable ? 'retry-chip--yes' : 'retry-chip--no'">
          {{ payload.data.retryable ? '可重试' : '不可重试' }}
        </span>
      </div>
      <p>{{ payload.data.message ?? '工具调用失败' }}</p>
      <small>
        {{ payload.data.toolName ?? 'unknown_tool' }}
        · {{ payload.data.category ?? 'unknown' }}
      </small>
    </div>
  </StructuredCardShell>
</template>

<style scoped>
.tool-error {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tool-error-head {
  display: flex;
}

.retry-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}

.retry-chip--yes {
  color: #166534;
  background: rgba(34, 197, 94, 0.14);
}

.retry-chip--no {
  color: #9f1239;
  background: rgba(244, 63, 94, 0.14);
}

.tool-error p {
  margin: 0;
  color: #9f1239;
}

.tool-error small {
  color: var(--text-subtle);
}
</style>
