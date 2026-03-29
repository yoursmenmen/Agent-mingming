<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { ChatMessage } from '../types/chat'
import type { RunStatus } from '../types/run'

const props = defineProps<{
  messages: ChatMessage[]
  draft: string
  runStatus: RunStatus
  errorMessage: string
  formatTime: (value: string) => string
}>()

const emit = defineEmits<{
  send: []
  'update:draft': [value: string]
}>()

const bottomAnchorRef = ref<HTMLElement | null>(null)

const draftValue = computed({
  get: () => props.draft,
  set: (value: string) => emit('update:draft', value),
})

const messageRenderKey = computed(() =>
  props.messages
    .map((message) => `${message.id}:${message.status ?? 'none'}:${message.content.length}`)
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
        <p>{{ message.content }}</p>
        <time>{{ formatTime(message.createdAt) }}</time>
      </article>
      <div ref="bottomAnchorRef" class="message-list-anchor" aria-hidden="true"></div>
    </div>

    <div class="chat-panel-footer">
      <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>

      <form class="composer" @submit.prevent="emit('send')">
        <textarea
          v-model="draftValue"
          class="composer-input"
          rows="3"
          placeholder="试试输入：帮我总结这个项目当前能力"
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
