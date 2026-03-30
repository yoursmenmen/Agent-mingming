<script setup lang="ts">
import { computed } from 'vue'
import CalcResultCard from './CalcResultCard.vue'
import ToolErrorCard from './ToolErrorCard.vue'
import UnknownStructuredCard from './UnknownStructuredCard.vue'
import WeatherCard from './WeatherCard.vue'
import type { ChatRole } from '../../types/chat'
import type {
  CalcResultStructuredPayload,
  StructuredPayload,
  ToolErrorStructuredPayload,
  UnknownStructuredPayload,
  WeatherStructuredPayload,
} from '../../types/structured'

type DisplayPayload =
  | WeatherStructuredPayload
  | CalcResultStructuredPayload
  | ToolErrorStructuredPayload
  | UnknownStructuredPayload

const props = defineProps<{
  role: ChatRole
  structured?: StructuredPayload | null
}>()

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isValidStructuredPayload(value: unknown): value is StructuredPayload {
  if (!isRecord(value)) {
    return false
  }

  if (typeof value.type !== 'string' || typeof value.version !== 'string' || !isRecord(value.data)) {
    return false
  }

  if (value.type === 'unknown' && typeof value.originalType !== 'string') {
    return false
  }

  return true
}

const payload = computed<DisplayPayload | null>(() => {
  if (props.role !== 'assistant' || props.structured === undefined || props.structured === null) {
    return null
  }

  if (!isValidStructuredPayload(props.structured)) {
    return null
  }

  return props.structured
})
</script>

<template>
  <div v-if="payload" class="structured-card-host" data-testid="structured-card-host">
    <WeatherCard v-if="payload.type === 'weather'" :payload="payload" />
    <CalcResultCard v-else-if="payload.type === 'calc_result'" :payload="payload" />
    <ToolErrorCard v-else-if="payload.type === 'tool_error'" :payload="payload" />
    <UnknownStructuredCard v-else :payload="payload" />
  </div>
</template>

<style scoped>
.structured-card-host {
  margin-top: 2px;
}
</style>
