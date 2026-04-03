<script setup lang="ts">
import type { RunEventMetrics } from '../types/run'

defineProps<{
  statusLabel: string
  runId: string
  timelineCount: number
  isRefreshing: boolean
  runMetrics: RunEventMetrics | null
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

    <div class="status-panel-body">
      <section class="status-section">
        <div class="status-section-head">
          <h3>运行概览</h3>
          <span class="status-window-pill">实时</span>
        </div>
        <dl class="status-grid status-grid--overview">
          <div>
            <dt>当前状态</dt>
            <dd>{{ statusLabel }}</dd>
          </div>
          <div>
            <dt>时间线条数</dt>
            <dd>{{ timelineCount }}</dd>
          </div>
          <div class="status-cell--wide">
            <dt>Run ID</dt>
            <dd class="run-id">{{ runId }}</dd>
          </div>
          <div class="status-cell--wide">
            <dt>视图模式</dt>
            <dd>聊天 + 时间线</dd>
          </div>
        </dl>
      </section>

      <section v-if="runMetrics" class="status-section">
        <div class="status-section-head">
          <h3>运行指标</h3>
          <span class="status-window-pill">近 {{ runMetrics.windowHours }} 小时</span>
        </div>
        <dl class="status-grid status-grid--metrics">
          <div>
            <dt>命令确认总数</dt>
            <dd>{{ runMetrics.confirm_total ?? 0 }}</dd>
          </div>
          <div>
            <dt>确认成功</dt>
            <dd>{{ runMetrics.confirm_success_total ?? 0 }}</dd>
          </div>
          <div>
            <dt>确认失败</dt>
            <dd>{{ runMetrics.confirm_failed_total ?? 0 }}</dd>
          </div>
          <div>
            <dt>确认拒绝</dt>
            <dd>{{ runMetrics.confirm_rejected_total ?? 0 }}</dd>
          </div>
          <div>
            <dt>工具调用数</dt>
            <dd>{{ runMetrics.tool_call_total ?? 0 }}</dd>
          </div>
          <div>
            <dt>工具错误数</dt>
            <dd>{{ runMetrics.tool_error_total ?? 0 }}</dd>
          </div>
          <div>
            <dt>契约告警数</dt>
            <dd>{{ runMetrics.contract_warning_total ?? 0 }}</dd>
          </div>
        </dl>
      </section>
    </div>
  </section>
</template>
