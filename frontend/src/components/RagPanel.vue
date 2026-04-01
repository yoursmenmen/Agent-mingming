<script setup lang="ts">
import { ref } from 'vue'
import type { RagDocuments, RagSourceInfo, RagSyncStatus } from '../types/run'

const props = defineProps<{
  ragSyncStatus: RagSyncStatus | null
  ragSources: RagSourceInfo[]
  ragDocuments: RagDocuments
  isRagRefreshing: boolean
  isRagTriggering: boolean
}>()

const emit = defineEmits<{
  refreshRag: []
  triggerRag: []
}>()

const showSources = ref(true)
const showLocalDocs = ref(false)
const showUrlDocs = ref(false)
</script>

<template>
  <section class="panel status-panel rag-panel">
    <div class="panel-header compact">
      <div>
        <p class="panel-kicker">RAG</p>
        <h2>知识库同步</h2>
      </div>
      <div class="rag-actions">
        <button class="ghost-button" type="button" @click="emit('refreshRag')" :disabled="isRagRefreshing">
          {{ isRagRefreshing ? '更新中…' : '刷新 RAG' }}
        </button>
        <button class="ghost-button" type="button" @click="emit('triggerRag')" :disabled="isRagTriggering">
          {{ isRagTriggering ? '触发中…' : '手动触发' }}
        </button>
      </div>
    </div>

    <div class="rag-quick-toggles">
      <button class="ghost-button rag-toggle" type="button" @click="showSources = !showSources">
        {{ showSources ? '收起' : '展开' }} URL 源
      </button>
      <button class="ghost-button rag-toggle" type="button" @click="showLocalDocs = !showLocalDocs">
        {{ showLocalDocs ? '收起' : '展开' }} 本地文档
      </button>
      <button class="ghost-button rag-toggle" type="button" @click="showUrlDocs = !showUrlDocs">
        {{ showUrlDocs ? '收起' : '展开' }} URL 文档
      </button>
    </div>

    <dl class="status-grid rag-status-grid" v-if="ragSyncStatus">
      <div>
        <dt>同步状态</dt>
        <dd>{{ ragSyncStatus.state }}</dd>
      </div>
      <div>
        <dt>Chunk / Embedding</dt>
        <dd>{{ ragSyncStatus.chunkCount }} / {{ ragSyncStatus.embeddingCount }}</dd>
      </div>
      <div>
        <dt>本地文档数</dt>
        <dd>{{ ragSyncStatus.sourceStats.localDocs }}</dd>
      </div>
      <div>
        <dt>已索引 URL 源数</dt>
        <dd>{{ ragSyncStatus.sourceStats.urlSources }}</dd>
      </div>
      <div>
        <dt>配置 URL 源数</dt>
        <dd>{{ props.ragSources.length }}</dd>
      </div>
    </dl>

    <p v-if="ragSyncStatus?.lastError" class="rag-error">{{ ragSyncStatus.lastError }}</p>

    <div class="rag-source-preview" v-if="props.ragSources.length > 0">
      <p>已配置 URL（预览）</p>
      <ul>
        <li v-for="item in props.ragSources.slice(0, 2)" :key="item.name + item.url">{{ item.url }}</li>
      </ul>
    </div>

    <div class="rag-panel-body">
      <section class="rag-section">
        <div class="rag-section-header">
          <h3>URL 源配置（{{ props.ragSources.length }}）</h3>
        </div>
        <div v-if="showSources">
        <ul class="rag-source-list" v-if="props.ragSources.length > 0">
          <li v-for="item in props.ragSources" :key="item.name + item.url">
            <strong>{{ item.name }}</strong>
            <span>{{ item.enabled ? '启用' : '停用' }} · {{ item.lastStatus }}</span>
            <small class="rag-url">{{ item.url }}</small>
            <small v-if="item.lastError" class="rag-error-inline">{{ item.lastError }}</small>
          </li>
        </ul>
        <p v-else class="rag-empty">未配置 URL 源</p>
        </div>
      </section>

      <section class="rag-section">
        <div class="rag-section-header">
          <h3>本地文档列表（{{ props.ragDocuments.localDocs.length }}）</h3>
        </div>
        <div v-if="showLocalDocs">
        <ul class="rag-doc-list" v-if="props.ragDocuments.localDocs.length > 0">
          <li v-for="path in props.ragDocuments.localDocs" :key="path">{{ path }}</li>
        </ul>
        <p v-else class="rag-empty">暂无本地文档索引</p>
        </div>
      </section>

      <section class="rag-section">
        <div class="rag-section-header">
          <h3>URL 文档列表（{{ props.ragDocuments.urlDocs.length }}）</h3>
        </div>
        <div v-if="showUrlDocs">
        <ul class="rag-doc-list" v-if="props.ragDocuments.urlDocs.length > 0">
          <li v-for="path in props.ragDocuments.urlDocs" :key="path">{{ path }}</li>
        </ul>
        <p v-else class="rag-empty">暂无 URL 文档索引</p>
        </div>
      </section>
    </div>
  </section>
</template>
