# Vector RAG PGVector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 docs 分片 + BM25 基础上，落地 pgvector 向量存储与混合召回，并保持启动不阻塞、增量同步与可观测性。

**Architecture:** 通过 Flyway 创建 `doc_chunk` 与 `doc_chunk_embedding` 表及向量索引，后台异步执行增量同步（upsert + 软删除），查询时执行向量召回与 BM25 召回后融合排序（RRF）。当向量链路未就绪或异常时自动降级 BM25-only，`RETRIEVAL_RESULT` 扩展策略与命中统计字段。

**Tech Stack:** Spring Boot 3.3, PostgreSQL + pgvector, Flyway, Spring AI Alibaba EmbeddingModel, JUnit5 + Mockito, Vue3 + TypeScript + Vitest。

---

## 文件结构与职责

- `backend/src/main/resources/db/migration/V3__vector_rag_schema.sql`
  - 新增：pgvector 扩展、向量表、索引、软删除约束。
- `backend/src/main/java/com/mingming/agent/entity/DocChunkEntity.java`
  - 新增：chunk 持久化实体。
- `backend/src/main/java/com/mingming/agent/entity/DocChunkEmbeddingEntity.java`
  - 新增：embedding 持久化实体。
- `backend/src/main/java/com/mingming/agent/repository/DocChunkRepository.java`
  - 新增：chunk 查询（按 docPath、isDeleted）。
- `backend/src/main/java/com/mingming/agent/repository/DocChunkEmbeddingRepository.java`
  - 新增：embedding 查询与 upsert 支持。
- `backend/src/main/java/com/mingming/agent/rag/VectorRagProperties.java`
  - 新增：模型名、version、topK、是否启用、后台同步开关。
- `backend/src/main/java/com/mingming/agent/rag/VectorChunkSyncService.java`
  - 新增：增量同步（diff + upsert + soft delete）。
- `backend/src/main/java/com/mingming/agent/rag/VectorRetrieverService.java`
  - 新增：向量召回（SQL cosine 距离）。
- `backend/src/main/java/com/mingming/agent/rag/HybridRetrievalService.java`
  - 新增：向量 + BM25 融合（RRF）。
- `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
  - 修改：调用 hybrid 检索，扩展事件 payload。
- `backend/src/main/java/com/mingming/agent/rag/RetrievalEventService.java`
  - 修改：追加 `strategy/vectorHitCount/bm25HitCount/finalHitCount/source`。
- `backend/src/test/java/com/mingming/agent/rag/VectorChunkSyncServiceTest.java`
  - 新增：增量同步三路径测试。
- `backend/src/test/java/com/mingming/agent/rag/HybridRetrievalServiceTest.java`
  - 新增：融合排序/降级测试。
- `backend/src/test/java/com/mingming/agent/rag/RetrievalEventServiceTest.java`
  - 修改：事件扩展字段断言。
- `frontend/src/services/eventMapper.ts`
  - 修改：展示 strategy + hitCount 统计摘要。
- `frontend/src/services/eventMapper.test.ts`
  - 修改：新增混合策略摘要断言。
- `docs/superpowers/reports/2026-03-30-vector-rag-implementation-report.md`
  - 新增：实现过程记录。

### Task 1: Flyway 迁移与持久化模型

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__vector_rag_schema.sql`
- Create: `backend/src/main/java/com/mingming/agent/entity/DocChunkEntity.java`
- Create: `backend/src/main/java/com/mingming/agent/entity/DocChunkEmbeddingEntity.java`
- Create: `backend/src/main/java/com/mingming/agent/repository/DocChunkRepository.java`
- Create: `backend/src/main/java/com/mingming/agent/repository/DocChunkEmbeddingRepository.java`
- Test: `backend/src/test/java/com/mingming/agent/rag/VectorChunkSyncServiceTest.java`

- [ ] **Step 1: 先写失败测试（实体与表映射）**

```java
@Test
void shouldMapChunkAndEmbeddingEntities() {
    DocChunkEntity chunk = new DocChunkEntity();
    chunk.setChunkId("c1");
    chunk.setDocPath("docs/a.md");
    chunk.setDeleted(false);
    assertThat(chunk.getChunkId()).isEqualTo("c1");

    DocChunkEmbeddingEntity embedding = new DocChunkEmbeddingEntity();
    embedding.setChunkId("c1");
    embedding.setEmbeddingModel("text-embedding-v3");
    assertThat(embedding.getChunkId()).isEqualTo("c1");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=VectorChunkSyncServiceTest test`
Expected: FAIL（实体/仓储/迁移尚未存在）。

- [ ] **Step 3: 最小实现迁移和实体**

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS doc_chunk (
  chunk_id varchar(64) PRIMARY KEY,
  doc_path varchar(512) NOT NULL,
  heading_path varchar(512) NOT NULL,
  content text NOT NULL,
  content_hash varchar(128) NOT NULL,
  is_deleted boolean NOT NULL DEFAULT false,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS doc_chunk_embedding (
  chunk_id varchar(64) PRIMARY KEY REFERENCES doc_chunk(chunk_id) ON DELETE CASCADE,
  embedding vector(1536) NOT NULL,
  embedding_model varchar(128) NOT NULL,
  embedding_version varchar(64) NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_doc_chunk_doc_path ON doc_chunk(doc_path);
CREATE INDEX IF NOT EXISTS idx_doc_chunk_is_deleted ON doc_chunk(is_deleted);
CREATE INDEX IF NOT EXISTS idx_doc_chunk_embedding_ivfflat
ON doc_chunk_embedding USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=VectorChunkSyncServiceTest test`
Expected: PASS。

- [ ] **Step 5: 提交 Task 1**

```bash
git add backend/src/main/resources/db/migration/V3__vector_rag_schema.sql backend/src/main/java/com/mingming/agent/entity/DocChunkEntity.java backend/src/main/java/com/mingming/agent/entity/DocChunkEmbeddingEntity.java backend/src/main/java/com/mingming/agent/repository/DocChunkRepository.java backend/src/main/java/com/mingming/agent/repository/DocChunkEmbeddingRepository.java
git commit -m "feat: add pgvector schema and persistence models for vector rag"
```

### Task 2: 增量同步（upsert + 软删除）

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/rag/VectorRagProperties.java`
- Create: `backend/src/main/java/com/mingming/agent/rag/VectorChunkSyncService.java`
- Create: `backend/src/test/java/com/mingming/agent/rag/VectorChunkSyncServiceTest.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/mingming/agent/rag/VectorChunkSyncServiceTest.java`

- [ ] **Step 1: 写失败测试（新增/更新/删除）**

```java
@Test
void sync_shouldUpsertChangedChunksAndSoftDeleteRemovedChunks() {
    // arrange existing db chunks + new chunks
    // act sync(docPath)
    // assert: new inserted, changed updated, removed marked deleted
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=VectorChunkSyncServiceTest test`
Expected: FAIL。

- [ ] **Step 3: 最小实现同步逻辑**

```java
if (isNewChunk) {
  insertChunk(); insertEmbedding();
} else if (contentHashChanged || modelChanged || versionChanged) {
  updateChunk(); upsertEmbedding();
}
for (oldChunkNotInNewSet) {
  markDeletedTrue(oldChunkId);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=VectorChunkSyncServiceTest test`
Expected: PASS。

- [ ] **Step 5: 提交 Task 2**

```bash
git add backend/src/main/java/com/mingming/agent/rag/VectorRagProperties.java backend/src/main/java/com/mingming/agent/rag/VectorChunkSyncService.java backend/src/test/java/com/mingming/agent/rag/VectorChunkSyncServiceTest.java backend/src/main/resources/application.yml
git commit -m "feat: implement incremental vector chunk sync with soft delete"
```

### Task 3: 向量检索 + 混合融合

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/rag/VectorRetrieverService.java`
- Create: `backend/src/main/java/com/mingming/agent/rag/HybridRetrievalService.java`
- Create: `backend/src/test/java/com/mingming/agent/rag/HybridRetrievalServiceTest.java`
- Modify: `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
- Test: `backend/src/test/java/com/mingming/agent/rag/HybridRetrievalServiceTest.java`

- [ ] **Step 1: 写失败测试（hybrid 优先级与降级）**

```java
@Test
void retrieve_shouldFallbackToBm25WhenVectorUnavailable() {}

@Test
void retrieve_shouldFuseVectorAndBm25ByRrf() {}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=HybridRetrievalServiceTest test`
Expected: FAIL。

- [ ] **Step 3: 实现向量检索与融合**

```java
List<Hit> vectorHits = vectorRetriever.search(query, vectorTopK);
List<Hit> bm25Hits = bm25Retriever.search(query, chunks, bm25TopK, threshold)
    .stream().map(Hit::fromBm25).toList();

List<Hit> finalHits = rrfFuse(vectorHits, bm25Hits, finalTopN);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=HybridRetrievalServiceTest,AgentOrchestratorTest test`
Expected: PASS。

- [ ] **Step 5: 提交 Task 3**

```bash
git add backend/src/main/java/com/mingming/agent/rag/VectorRetrieverService.java backend/src/main/java/com/mingming/agent/rag/HybridRetrievalService.java backend/src/test/java/com/mingming/agent/rag/HybridRetrievalServiceTest.java backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java
git commit -m "feat: add hybrid retrieval with pgvector and bm25 fallback"
```

### Task 4: 检索事件扩展与前端摘要

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/rag/RetrievalEventService.java`
- Modify: `backend/src/test/java/com/mingming/agent/rag/RetrievalEventServiceTest.java`
- Modify: `frontend/src/services/eventMapper.ts`
- Modify: `frontend/src/services/eventMapper.test.ts`

- [ ] **Step 1: 写失败测试（strategy 与统计字段）**

```ts
it('should summarize hybrid retrieval with hit counts', () => {
  const summary = summarizePayload({
    strategy: 'hybrid',
    vectorHitCount: 3,
    bm25HitCount: 4,
    finalHitCount: 3,
    query: '下一阶段计划'
  }, 'RETRIEVAL_RESULT')
  expect(summary).toContain('hybrid')
  expect(summary).toContain('3')
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && npm run test:unit -- src/services/eventMapper.test.ts`
Expected: FAIL。

- [ ] **Step 3: 实现事件扩展与前端映射**

```java
payload.put("strategy", strategy);
payload.put("vectorHitCount", vectorHitCount);
payload.put("bm25HitCount", bm25HitCount);
payload.put("finalHitCount", finalHitCount);
```

```ts
return `策略: ${strategy} | 向量:${vectorHitCount} BM25:${bm25HitCount} 最终:${finalHitCount} | 查询: ${query}`
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd frontend && npm run test:unit -- src/services/eventMapper.test.ts`
Expected: PASS。

- [ ] **Step 5: 提交 Task 4**

```bash
git add backend/src/main/java/com/mingming/agent/rag/RetrievalEventService.java backend/src/test/java/com/mingming/agent/rag/RetrievalEventServiceTest.java frontend/src/services/eventMapper.ts frontend/src/services/eventMapper.test.ts
git commit -m "feat: extend retrieval event metadata for hybrid strategy"
```

### Task 5: 后台异步同步与报告

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/rag/VectorRagBootstrapSync.java`
- Create: `docs/superpowers/reports/2026-03-30-vector-rag-implementation-report.md`
- Modify: `docs/changes-log.md`

- [ ] **Step 1: 写失败测试（启动不阻塞）**

```java
@Test
void bootstrapSync_shouldRunAsyncWithoutBlockingStartup() {}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=VectorRagBootstrapSyncTest test`
Expected: FAIL。

- [ ] **Step 3: 实现异步同步组件并补文档**

```java
@EventListener(ApplicationReadyEvent.class)
public void warmup() {
    taskExecutor.execute(() -> vectorChunkSyncService.syncAllDocsIncremental());
}
```

- [ ] **Step 4: 运行全量验证**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn test`
Expected: PASS。

Run: `cd frontend && npm run test:unit && npm run build && npm run lint`
Expected: PASS。

- [ ] **Step 5: 提交 Task 5**

```bash
git add backend/src/main/java/com/mingming/agent/rag/VectorRagBootstrapSync.java docs/superpowers/reports/2026-03-30-vector-rag-implementation-report.md docs/changes-log.md
git commit -m "feat: add async vector sync bootstrap and implementation report"
```

## 计划自检

### Spec 覆盖
- pgvector 模型与迁移：Task 1
- 增量 upsert + 软删除：Task 2
- 混合召回 + 降级：Task 3
- 事件扩展 + 前端可观测：Task 4
- 启动异步 + 过程记录：Task 5

### 占位符扫描
- 无 TBD/TODO/“后续补充”占位。

### 一致性检查
- 过期判定统一：`content_hash/embedding_model/embedding_version`
- 事件统计字段统一：`strategy/vectorHitCount/bm25HitCount/finalHitCount`
