# 向量阶段设计（pgvector + 混合召回）

## 1. 目标

在现有 docs 分片 + BM25 RAG 基础上，升级为 **pgvector 向量检索 + BM25 混合召回**，并保持：

1. 启动不阻塞（异步增量同步）
2. 检索可观测（时间线可回放）
3. 兼容降级（向量不可用时退回 BM25）

## 2. 范围

- 存储：PostgreSQL + pgvector
- 召回：Vector + BM25 混合
- 同步：增量 upsert + 软删除（不做每次全量重建）
- 事件：扩展 `RETRIEVAL_RESULT` 检索元信息

## 3. 非目标

- 本阶段不接入外部 URL/第三方知识源
- 本阶段不改前端主聊天区卡片样式
- 本阶段不引入独立向量数据库（Qdrant 等）

## 4. 数据模型

### 4.1 表结构

1) `doc_chunk`
- `chunk_id` (pk)
- `doc_path`
- `heading_path`
- `content`
- `content_hash`
- `is_deleted`
- `updated_at`

2) `doc_chunk_embedding`
- `chunk_id` (pk/fk -> doc_chunk.chunk_id)
- `embedding vector(n)`
- `embedding_model`
- `embedding_version`
- `updated_at`

### 4.2 索引

- 文本侧：`doc_path`, `is_deleted`
- 向量侧：`ivfflat (embedding vector_cosine_ops)`

说明：小数据量允许先顺序扫描；数据规模上升后调优 ivfflat 参数（如 `lists`）。

## 5. 过期判定与增量同步

判定“需重算 embedding”条件（任一满足）：

1. `content_hash` 变化
2. `embedding_model` 变化
3. `embedding_version` 变化

同步流程（按文件）：

1. 重新分片得到新 chunk 集
2. 与库内旧 chunk 做 diff
3. 处理策略：
   - 新增：插入 chunk + 生成向量
   - 变更：upsert + 重算向量
   - 删除：`is_deleted=true`（软删除）

## 6. 运行时行为

### 6.1 启动策略

- 服务启动后立即可接收请求
- 后台异步执行“增量同步任务”
- 同步未完成阶段，检索自动退回 BM25-only

### 6.2 查询策略

- Vector 召回：`vectorTopK`
- BM25 召回：`bm25TopK`
- 融合：RRF（推荐）
- 输出：`finalTopN`

## 7. 可观测性

扩展 `RETRIEVAL_RESULT` payload 字段：

- `strategy`: `hybrid | vector | bm25`
- `vectorHitCount`
- `bm25HitCount`
- `finalHitCount`
- `hits[]`:
  - `chunkId`
  - `docPath`
  - `score`
  - `snippet`
  - `source` (`vector|bm25|both`)

前端时间线 summary 显示策略和命中统计，raw payload 显示明细。

```
{
  "hits": [
    {
      "score": 0.03278688524590164,
      "source": "hybrid",
      "chunkId": "21affcfb94b84dc8",
      "docPath": "superpowers/specs/2026-03-30-docs-rag-bm25-design.md",
      "snippet": "当前项目已完成 Tool Calling 与结构化卡片阶段，下一步进入 RAG 最小闭环。结合现有技术栈与迭代节奏，本阶段聚焦本地 `docs/` 目录，优先实现低风险、可观测、可演进的检索增强问答。\n\n本阶段目标：\n\n1. 在后端增加 `docs/` 文档分片与 BM25 检索能力。\n2. 在聊天编排链路中接入检索上下文，提升回答的项目文档相关性。\n3. 在 run timeline 中新增检索",
      "headingPath": "docs 分片 + BM25 最小 RAG 设计 > 1. 背景与目标"
    },
    {
      "score": 0.016129032258064516,
      "source": "vector",
      "chunkId": "c54817fd0450b348",
      "docPath": "superpowers/plans/2026-03-30-docs-rag-bm25.md",
      "snippet": "`docs/superpowers/reports/2026-03-30-docs-rag-bm25-implementation-report.md`\n  - 新增：实现过程记录模板（目标、过程、验证、风险）。\n- `docs/changes-log.md`\n  - 修改：记录本次 RAG 阶段变更。",
      "headingPath": "Docs RAG BM25 Implementation Plan > 文件结构与职责"
    },
    {
      "score": 0.016129032258064516,
      "source": "bm25",
      "chunkId": "cd4e0aac9f7b8cb5",
      "docPath": "changes-log.md",
      "snippet": "- 使用 Vite 的 Vue TS 模板初始化\n\n> 说明：目前是脚手架状态，下一步会实现 Chat + Trace 页面并对接后端 SSE。",
      "headingPath": "变更记录（Claude 做了什么） > 4) 前端"
    }
  ],
  "query": "我项目中的rag到哪一步了",
  "hitCount": 3,
  "strategy": "hybrid",
  "bm25HitCount": 3,
  "finalHitCount": 3,
  "vectorHitCount": 3
}
```

## 8. 错误处理

- 向量查询异常：warn 日志 + 退回 BM25
- embedding 失败：记录失败计数，不中断主链路
- 索引未就绪：标记状态为 warming，保持 BM25 可用

## 9. 测试与验收

### 9.1 自动化测试

- Flyway 迁移成功（含 pgvector 扩展与表结构）
- 增量同步：新增/变更/删除三路径
- 过期判定：hash/model/version 触发重算
- 检索融合：hybrid 结果稳定且可降级
- 事件 payload：字段完整、前端可解析

### 9.2 联调验收

1. 提问 docs 问题，看到 `strategy=hybrid`
2. 关闭向量路径后自动 `strategy=bm25`
3. 修改 fixture 文档后，增量同步只更新变更 chunk

## 10. 实现记录要求

保持“目标 + 过程”双文档：

- 目标与设计：本 spec
- 实施过程报告：`docs/superpowers/reports/YYYY-MM-DD-vector-rag-implementation-report.md`

## 11. 文档依据

- pgvector 文档（向量列、cosine 距离、ivfflat/hnsw）
- Spring AI 参考（RAG、VectorStore 检索模式）
