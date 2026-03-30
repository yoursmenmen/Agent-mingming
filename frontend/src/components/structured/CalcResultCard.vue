<script setup lang="ts">
import StructuredCardShell from './StructuredCardShell.vue'
import type { CalcResultStructuredPayload } from '../../types/structured'

function formatNumber(value: unknown): string {
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '--'
}

function formatUnit(value: unknown): string {
  return typeof value === 'string' && value.trim().length > 0 ? value : ''
}

defineProps<{
  payload: CalcResultStructuredPayload
}>()
</script>

<template>
  <StructuredCardShell title="计算结果" subtitle="calc_result.v1" data-testid="calc-result-card">
    <div class="calc-row">
      <code>{{ payload.data.expression ?? '未知表达式' }}</code>
      <strong>
        {{ formatNumber(payload.data.result) }}
        <template v-if="formatNumber(payload.data.result) !== '--' && formatUnit(payload.data.unit)">
          {{ ` ${formatUnit(payload.data.unit)}` }}
        </template>
      </strong>
    </div>
  </StructuredCardShell>
</template>

<style scoped>
.calc-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

code {
  font-size: 12px;
}

strong {
  font-size: 14px;
  color: var(--text-main);
}
</style>
