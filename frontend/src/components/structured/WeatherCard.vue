<script setup lang="ts">
import StructuredCardShell from './StructuredCardShell.vue'
import type { WeatherStructuredPayload } from '../../types/structured'

function formatNumber(value: unknown): string {
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : '--'
}

defineProps<{
  payload: WeatherStructuredPayload
}>()
</script>

<template>
  <StructuredCardShell title="天气卡片" subtitle="weather.v1" data-testid="weather-card">
    <div class="structured-grid">
      <span>城市</span>
      <strong>{{ payload.data.city ?? '未知' }}</strong>
      <span>天气</span>
      <strong>{{ payload.data.condition ?? '未知' }}</strong>
      <span>温度</span>
      <strong>{{ formatNumber(payload.data.tempC) }}<template v-if="formatNumber(payload.data.tempC) !== '--'">°C</template></strong>
    </div>
  </StructuredCardShell>
</template>

<style scoped>
.structured-grid {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 8px;
}

.structured-grid span {
  color: var(--text-subtle);
}

.structured-grid strong {
  color: var(--text-main);
  font-weight: 600;
}
</style>
