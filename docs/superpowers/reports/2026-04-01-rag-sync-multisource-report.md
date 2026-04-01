# 2026-04-01 RAG 同步可观测与多知识源接入报告

## 本次目标

1. 完成 RAG 同步状态可观测能力（状态查询 + 手动触发）。
2. 补齐同步生命周期状态流转与 `RAG_SYNC` 事件。
3. 接入 URL 知识源并纳入现有增量同步链路。
4. 扩展 `doc_chunk` source 字段，支持多源数据标注。
5. 完成前端最小展示（`RAG_SYNC` 时间线摘要）与文档更新。

## 交付清单

### 后端接口与状态

- 新增 `RagSyncController`：
  - `GET /api/rag/sync/status`
  - `POST /api/rag/sync/trigger`
- 新增 `SyncStatusService`：
  - 状态快照：`state/lastStartAt/lastSuccessAt/lastError/chunkCount/embeddingCount/sourceStats`
  - 生命周期状态：`running -> completed/failed`

### 生命周期事件

- `RunEventType` 新增 `RAG_SYNC`。
- `RetrievalEventService` 新增 `recordRagSync(...)`，用于 started/completed/failed 生命周期事件记录。

### 多知识源 URL 接入

- 新增 `UrlSourceProperties`（`agent.rag.sources.url`）。
- 新增 `UrlSourceIngestionService`：
  - URL 抓取（WebClient）
  - 基础正文清洗（HTML/script/style 清理）
  - 复用现有 chunking 逻辑
  - 失败源跳过，不中断整体同步
- `VectorChunkSyncService` 接入 URL chunks，与本地 docs chunks 合并同步。

### 数据模型扩展

- Flyway 新增 `V5__rag_source_columns.sql`：
  - `doc_chunk.source_type`
  - `doc_chunk.source_id`
  - 兼容旧数据默认值与回填
- `DocChunkEntity`、`VectorChunkSyncService`、`VectorRetrieverService` 已适配 source 字段。

### 前端最小展示

- `eventMapper` 新增 `RAG_SYNC` 摘要映射：
  - started: `RAG 同步开始`
  - completed: 展示 `新增/更新/软删除/未变更`
  - failed: 展示失败原因

## 验证与测试

新增/更新测试：

- `RagSyncControllerTest`
- `VectorRagSyncLifecycleTest`
- `UrlSourceIngestionServiceTest`
- `VectorChunkSourceFieldsTest`
- `eventMapper.test.ts`（新增 `RAG_SYNC` 覆盖）

## 已知限制

1. 当前 `RAG_SYNC` 事件记录在无 run 上下文时走日志路径；如需统一进入会话时间线，可在后续引入 system run/session 归档策略。
2. URL 正文提取为基础实现）（正则清洗，复杂站点可后续接入更强提取器。
