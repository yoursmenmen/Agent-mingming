<script setup lang="ts">
defineProps<{
  title: string
  subtitle?: string
}>()
</script>

<template>
  <section class="structured-card-shell" data-testid="structured-card-shell">
    <header class="structured-card-shell__header">
      <h3>{{ title }}</h3>
      <p v-if="subtitle">{{ subtitle }}</p>
    </header>
    <div class="structured-card-shell__body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.structured-card-shell {
  margin-top: 8px;
  padding: 10px;
  border-radius: 12px;
  border: 1px solid rgba(217, 70, 128, 0.2);
  background: rgba(255, 255, 255, 0.7);
}

.structured-card-shell__header {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 6px;
}

.structured-card-shell__header h3 {
  margin: 0;
  font-size: 13px;
  color: var(--text-main);
}

.structured-card-shell__header p {
  margin: 0;
  font-size: 11px;
  color: var(--text-subtle);
}

.structured-card-shell__body {
  font-size: 12px;
}
</style>
