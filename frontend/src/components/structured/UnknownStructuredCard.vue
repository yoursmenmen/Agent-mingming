<script setup lang="ts">
import { computed } from 'vue'
import StructuredCardShell from './StructuredCardShell.vue'
import type { UnknownStructuredPayload } from '../../types/structured'

const props = defineProps<{
  payload: UnknownStructuredPayload
}>()

function toSafeValue(value: unknown): string {
  if (value === null) {
    return 'null'
  }

  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }

  if (Array.isArray(value)) {
    return `[array:${value.length}]`
  }

  if (typeof value === 'object') {
    return '[object]'
  }

  return String(value)
}

const entries = computed(() =>
  Object.entries(props.payload.data).map(([key, value]) => ({
    key,
    value: toSafeValue(value),
  })),
)
</script>

<template>
  <StructuredCardShell title="未识别结构化数据" subtitle="fallback" data-testid="unknown-structured-card">
    <div class="unknown-wrap">
      <p>类型：{{ payload.originalType }}</p>
      <ul v-if="entries.length > 0" class="unknown-list">
        <li v-for="entry in entries" :key="entry.key">
          <span>{{ entry.key }}</span>
          <strong>{{ entry.value }}</strong>
        </li>
      </ul>
      <p v-else>无可展示字段</p>
    </div>
  </StructuredCardShell>
</template>

<style scoped>
.unknown-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.unknown-wrap p {
  margin: 0;
  color: var(--text-subtle);
}

.unknown-list {
  margin: 0;
  padding-left: 16px;
  display: grid;
  gap: 4px;
}

.unknown-list li {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.unknown-list span {
  color: var(--text-subtle);
}

.unknown-list strong {
  font-weight: 600;
  margin: 0;
  font-size: 11px;
  color: var(--text-main);
}
</style>
