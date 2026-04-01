# RAG 同步可观测 + 多知识源接入设计

## 目标

1. 为向量 RAG 增量同步提供可观测能力（状态、统计、错误）。
2. 在现有 `docs/` 本地源之外，新增 URL 知识源接入。
3. 保持“非阻塞启动 + 失败降级 BM25”原则。

## 范围

- 后端新增同步状态查询接口与手动触发接口。
- 后端新增 URL 源抓取、切分、增量同步。
- 统一进入现有 chunk + embedding + hybrid 检索链路。

## 非目标

- 不做前端大改，仅在现有 timeline/status 面板显示关键信息。
- 不做复杂调度系统（先用本地调度/手动触发）。
- 不引入新向量数据库（继续 pgvector）。

## 设计

### 1) 同步可观测

新增状态对象（内存 + 持久化摘要）：

- `state`: `idle | warming | running | failed`
- `lastStartAt`, `lastSuccessAt`
- `lastError`
- `chunkCount`, `embeddingCount`
- `sourceStats`（`localDocs`、`urlSources`）

新增接口：

- `GET /api/rag/sync/status`
- `POST /api/rag/sync/trigger`

新增事件：

- `RAG_SYNC`（started/completed/failed）

### 2) 多知识源（URL）

新增配置：

- `agent.rag.sources.localDocs.enabled`
- `agent.rag.sources.url.enabled`
- `agent.rag.sources.url.items[]`（name/url/enabled）

抓取流程：

1. 拉取 URL 文本（优先 markdown/text）
2. 清洗正文
3. 进入统一 chunking
4. 走增量 upsert + 软删除

数据字段扩展：

- `doc_chunk.source_type` (`local_docs|url`)
- `doc_chunk.source_id`（如 `url:official-docs`）

### 3) 检索与降级

- 检索逻辑不变：`vector + bm25` 混合融合。
- 当 URL 源抓取失败时，仅记录错误与统计，不影响本地 docs 检索。

## 验收标准

1. 可通过 API 查看同步状态与最近错误。
2. 手动触发同步后状态有完整生命周期变化。
3. URL 源可入库并参与召回。
4. 某个 URL 源失败时主链路不受影响。
