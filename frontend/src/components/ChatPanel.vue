<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import StructuredCardHost from './structured/StructuredCardHost.vue'
import type { ChatMessage } from '../types/chat'
import type { OnboardingPlanCard, PendingMcpAction, RunStatus } from '../types/run'
import { renderMarkdown } from '../services/markdown'

const props = defineProps<{
  messages: ChatMessage[]
  draft: string
  runStatus: RunStatus
  errorMessage: string
  pendingMcpActions?: PendingMcpAction[]
  onboardingPlanCard?: OnboardingPlanCard | null
  handlingActionIds?: string[]
  formatTime: (value: string) => string
}>()

const emit = defineEmits<{
  send: []
  'update:draft': [value: string]
  'confirm-pending-action': [actionId: string]
  'reject-pending-action': [actionId: string]
  'apply-onboarding-plan': [runInstall: boolean]
  'dismiss-onboarding-plan': []
}>()

const bottomAnchorRef = ref<HTMLElement | null>(null)

const draftValue = computed({
  get: () => props.draft,
  set: (value: string) => emit('update:draft', value),
})

const messageRenderKey = computed(() =>
  props.messages
    .map((message) => `${message.id}:${message.status ?? 'none'}:${message.content.length}:${message.structured !== undefined ? 's' : 'n'}`)
    .join('|'),
)

async function scrollToBottom() {
  await nextTick()
  bottomAnchorRef.value?.scrollIntoView({ block: 'end', behavior: 'auto' })
  requestAnimationFrame(() => {
    bottomAnchorRef.value?.scrollIntoView({ block: 'end', behavior: 'auto' })
  })
}

watch(
  messageRenderKey,
  () => {
    void scrollToBottom()
  },
  { flush: 'post' },
)

watch(
  () => props.runStatus,
  () => {
    void scrollToBottom()
  },
  { flush: 'post' },
)

onMounted(() => {
  void scrollToBottom()
})

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.isComposing) {
    return
  }

  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    emit('send')
  }
}

function toMessageHtml(message: ChatMessage): string {
  if (message.role !== 'assistant') {
    return message.content
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&#39;')
      .replaceAll('\n', '<br/>')
  }
  return renderMarkdown(message.content)
}

const pendingActions = computed(() => props.pendingMcpActions ?? [])
const onboardingPlanCard = computed(() => props.onboardingPlanCard ?? null)
const handlingActionIds = computed(() => props.handlingActionIds ?? [])
</script>

<template>
  <section class="panel chat-panel">
    <div class="panel-header">
      <div>
        <p class="panel-kicker">Chat</p>
        <h2>对话区</h2>
      </div>
      <p class="panel-tip">后端接口：POST /api/chat/stream</p>
    </div>

    <div class="message-list">
      <article
        v-for="message in messages"
        :key="message.id"
        class="message"
        :class="[
          `message--${message.role}`,
          message.status ? `message--${message.status}` : '',
          message.role === 'assistant' && message.status === 'done' ? 'message--assistant-done' : '',
        ]"
      >
        <div class="message-topline">
          <div class="message-badge">{{ message.role === 'user' ? '你' : 'Agent' }}</div>
          <span v-if="message.role === 'assistant' && message.status === 'streaming'" class="message-live-chip">
            实时生成中
          </span>
        </div>
        <div class="message-markdown" v-html="toMessageHtml(message)"></div>
        <StructuredCardHost :role="message.role" :structured="message.structured" />
        <time>{{ formatTime(message.createdAt) }}</time>
      </article>
      <div ref="bottomAnchorRef" class="message-list-anchor" aria-hidden="true"></div>
    </div>

    <div class="chat-panel-footer">
      <div v-if="onboardingPlanCard" class="pending-actions-banner onboarding-plan-banner">
        <p><strong>MCP 接入计划已生成</strong></p>
        <ul>
          <li class="pending-action-item pending-action-item--stacked">
            <div class="onboarding-plan-lines">
              <span>仓库：{{ onboardingPlanCard.repoUrl }}</span>
              <span>服务名：{{ onboardingPlanCard.serverName }} | 传输：{{ onboardingPlanCard.preferredTransport }}</span>
              <span>启动命令：{{ onboardingPlanCard.startupCommand || '未识别' }}</span>
              <span>安装命令数：{{ onboardingPlanCard.installCommands.length }}</span>
              <span v-if="onboardingPlanCard.requiredEnv.length">
                必填环境变量：{{ onboardingPlanCard.requiredEnv.join(', ') }}
              </span>
              <span v-if="onboardingPlanCard.missingRequiredEnv.length" class="onboarding-plan-warn">
                缺失环境变量：{{ onboardingPlanCard.missingRequiredEnv.join(', ') }}
              </span>
              <span v-if="onboardingPlanCard.warnings.length">提示：{{ onboardingPlanCard.warnings.join('；') }}</span>
            </div>
            <div class="pending-action-controls">
              <button
                class="ghost-button"
                type="button"
                :disabled="onboardingPlanCard.state === 'APPLYING'"
                @click="emit('apply-onboarding-plan', false)"
              >
                {{ onboardingPlanCard.state === 'APPLYING' ? '执行中…' : '接入（不安装）' }}
              </button>
              <button
                class="ghost-button"
                type="button"
                :disabled="onboardingPlanCard.state === 'APPLYING'"
                @click="emit('apply-onboarding-plan', true)"
              >
                接入并安装
              </button>
              <button
                class="ghost-button ghost-button--danger"
                type="button"
                :disabled="onboardingPlanCard.state === 'APPLYING'"
                @click="emit('dismiss-onboarding-plan')"
              >
                拒绝
              </button>
            </div>
          </li>
        </ul>
      </div>

      <div v-if="pendingActions.length" class="pending-actions-banner">
        <p><strong>命令待你确认</strong>（未执行）</p>
        <ul>
          <li v-for="action in pendingActions" :key="action.actionId" class="pending-action-item">
            <span>{{ action.summary }}</span>
            <div class="pending-action-controls">
              <button
                class="ghost-button"
                type="button"
                :disabled="handlingActionIds.includes(action.actionId) || action.state === 'PROCESSING'"
                @click="emit('confirm-pending-action', action.actionId)"
              >
                {{ handlingActionIds.includes(action.actionId) || action.state === 'PROCESSING' ? '处理中…' : '确认执行' }}
              </button>
              <button
                class="ghost-button ghost-button--danger"
                type="button"
                :disabled="handlingActionIds.includes(action.actionId) || action.state === 'PROCESSING'"
                @click="emit('reject-pending-action', action.actionId)"
              >
                拒绝
              </button>
            </div>
          </li>
        </ul>
      </div>

      <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>

      <form class="composer" @submit.prevent="emit('send')">
        <textarea
          v-model="draftValue"
          class="composer-input"
          rows="3"
          placeholder="试试输入：帮我总结这个项目当前能力"
          @keydown="handleComposerKeydown"
        />
        <div class="composer-footer">
          <span>使用固定开发 token，经由 Vite 代理访问后端</span>
          <button class="send-button" type="submit" :disabled="runStatus === 'streaming'">
            {{ runStatus === 'streaming' ? '生成中…' : '发送消息' }}
          </button>
        </div>
      </form>
    </div>
  </section>
</template>
