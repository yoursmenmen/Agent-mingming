# Agent-MM

> 目标：把项目当前实现、技术决策、可优化方向和横向对比整理成“可直接回答面试官”的素材。

## 1. 项目定位与架构一句话

这是一个面向 AI Agent 工程化的 monorepo MVP：后端用 Spring Boot + Spring AI（DashScope）做编排、工具调用和 RAG，当前 provider 用的是 Spring AI Alibaba。这样既利用阿里生态能力，也保留后续切换模型供应商的接口兼容性。前端用 Vue3 展示流式对话与运行时间线，PostgreSQL 持久化 run/event 以支持回放、排障和后续评测。

---

## 2. 对话链路（SSE + Run Trace）

### 当前实现

- 入口接口：`POST /api/chat/stream`，通过 SSE 推送 `run` 和 `event`。
- 每次请求创建 `runId`，可复用 `sessionId` 进行多轮对话。
- 持久化核心事件：`USER_MESSAGE`、`MODEL_MESSAGE`、`TOOL_CALL`、`TOOL_RESULT`、`RETRIEVAL_RESULT`、`RAG_SYNC`、`ERROR`。
- 历史查询支持 run 维度与 session 维度：`/api/runs/{runId}/events`、`/api/sessions/{sessionId}/events`。

### 可优化点

- 增加 trace 过滤与分页（按 event type、时间区间、session）。
- 增加请求级指标（首 token 延迟、总耗时、工具调用耗时）。
- 从“仅事件回放”升级到“可视化链路诊断”（类似 mini APM）。

### 技术选型与横向对比

| 方案 | 当前选择 | 优势 | 代价 |
|---|---|---|---|
| 流式协议 | SSE over POST | 浏览器和网关兼容性好，实现简单 | 单向通道，不适合复杂双向协商 |
| 追踪存储 | 事件落库（JSON payload） | 天然可回放、可观测、可审计 | 需要做事件 schema 治理 |
| 会话记忆 | session 聚合历史并裁剪窗口 | 成本低，足够支撑 MVP 多轮 | 长上下文质量受限 |

---

## 3. Tool Calling（本地工具闭环）

### 当前实现

- 通过 Spring AI 工具机制接入本地工具（如 `now`、`add`、`get_weather`）。
- 编排器统一注册工具，模型可自动决策调用。
- 工具调用与结果均写入事件流，可在时间线回放。

### 可优化点

- 增加工具级超时、重试和熔断策略。
- 工具参数做更严格校验（schema + 边界检查）。
- 引入工具权限分级（按环境、按用户、按会话白名单）。

### 技术选型与横向对比

| 方案 | 当前选择 | 优势 | 代价 |
|---|---|---|---|
| 工具接入 | 本地 `@Tool` + 统一注册 | 开发快，调试链路短 | 扩展到外部系统时治理压力上升 |
| 工具可观测 | `TOOL_CALL/TOOL_RESULT` 事件化 | 问题定位清晰 | 需要定义 payload 兼容策略 |
| 上下文传递 | ToolContext | 流式线程切换更稳 | 需要约束上下文键命名 |

---

## 4. RAG：当前做到哪里了

### 当前实现

- 数据源：本地 `docs/**/*.md` + URL 文档源。
- 预处理：Markdown 分块（标题路径、段落切分、稳定 chunkId）。
- 召回：BM25 + 向量检索（pgvector）并行，RRF 融合得到最终 topN。
- 同步：按 `content_hash + embedding_model + embedding_version` 做增量更新，支持 upsert + 软删除。
- 可观测：`RETRIEVAL_RESULT` 记录 strategy、向量/BM25 命中数、最终命中数和样本来源。

### 可优化点（你面试可以重点讲）

1. **重排器（Reranker / Cross-Encoder）**
   - 现状：RRF 主要依赖召回排名，语义精排能力有限。
   - 目标：对候选片段做二阶段精排，提高 Top1/Top3 准确率。

2. **可追溯回答（Citation）**
   - 现状：可看到文档路径和事件，但回答未强绑定引用片段。
   - 目标：回答结构化输出 `answer + citations[]`（chunkId/snippet/score）。

3. **查询改写（Multi-Query / HyDE / Decomposition）**
   - 现状：单 query 检索，复杂问题容易漏召。
   - 目标：改写后并行召回并融合，提高 recall。

4. **离线评测体系**
   - 现状：有可观测日志，但缺少稳定离线指标闭环。
   - 目标：建立 Recall@K、MRR、nDCG、答案可归因率等基线。

### 技术选型与横向对比

| 维度 | 当前方案 | 可升级方案 | 对比结论 |
|---|---|---|---|
| 关键词召回 | BM25（自实现） | Elasticsearch/OpenSearch BM25 | 当前足够轻量，规模上来后可迁移搜索引擎 |
| 语义召回 | pgvector + embedding | 专用向量库（Milvus/Weaviate） | 现阶段用 PG 一体化性价比高 |
| 融合 | RRF | 学习排序/重排模型 | RRF 稳定易解释，重排提升上限更高 |
| 更新策略 | 增量同步 + 软删除 | 事件驱动 CDC + 分层索引 | 当前简单可靠，后期可做实时化 |

---

## 5. URL Source 与条件请求（ETag）

### 当前实现

- URL 抓取支持 `If-None-Match` / `If-Modified-Since` 条件请求。
- 同步状态支持 `SUCCESS/FAILED/NOT_MODIFIED/...`。
- 已修复关键问题：当 304 或抓取失败时复用已有 chunk，避免误软删除导致“文档读不到”。
- 增加了 URL 同步日志：每个 source 的同步结果、复用 chunk 数、总 chunk 数。

### 可优化点

- 支持 sitemap 和站点爬取策略（同域、深度、页数、白名单）。
- 增加 canonical 去重与正文提取质量控制（boilerplate 去除）。
- 建立抓取队列（优先级、重试、退避、死信队列）。

### 技术选型与横向对比

| 方案 | 当前选择 | 优势 | 代价 |
|---|---|---|---|
| 抓取模式 | 单 URL 拉取 | 可控、简单、稳定 | 覆盖面受限 |
| 缓存协商 | ETag/Last-Modified | 带宽与同步开销低 | 首次冷启动仍需全量 |
| 文本抽取 | 简化清洗（HTML 去标签） | 快速落地 | 正文质量受页面结构影响 |

---

## 6. MCP 现状与“Python 爬虫 MCP”方向

### 当前实现

- MCP 目前是配置与接口骨架，工具发现/调用链路尚未打通。

### 为什么 Python 适合做 crawler MCP

- 生态成熟：`httpx`、`beautifulsoup4`、`readability-lxml`、`trafilatura`。
- 迭代快：抓取策略、解析规则和反爬兼容通常 Python 更高效。
- 易服务化：可独立部署为 MCP server，被 Java 编排层调用。

### 推荐的 MCP 工具设计（面试可讲）

- `search_docs(query, topK)`：搜索入口。
- `fetch_page(url)`：返回正文、标题、links、etag、lastModified。
- `enqueue_ingest(url, source)`：异步入库任务。
- `get_ingest_job(jobId)`：查询任务状态。

> 关键原则：让 Agent 在“受控策略”内选链接，不是无限自由爬取。

---

## 7. 前端控制台能力

### 当前实现

- Chat 区支持流式增量展示。
- Inspector 拆分状态/RAG/时间线/工具面板。
- 时间线支持 session 级历史回放。
- 最新调整：模型输出中不展示流式 delta 时间线，结束后统一刷新历史事件。

### 可优化点

- 时间线筛选（按 event type）和聚合视图（同类事件折叠）。
- 前端增加 run 诊断看板（token 延迟、检索命中、工具成功率）。
- 引用卡片化展示（回答片段可跳转至来源 chunk）。

---

## 8. 主流还没做但值得做的点（可作为 roadmap）

1. Reranker（二阶段精排）。
2. 回答引用（可追溯与可验证）。
3. 查询改写（多路召回融合）。
4. Prompt/检索评测平台（离线集 + 自动回归）。
5. 多租户隔离与权限模型（企业场景必问）。
6. 成本治理（token、embedding、工具调用成本可视化）。

---

## 9. 面试高频问答模板（可直接背）

### Q1：为什么用“事件落库”而不是只存最终回答？

我把运行过程拆成事件流，是为了让系统具备可观测和可回放能力。只存最终回答很难排查“模型为什么这么答”，而事件化后可以看到检索命中、工具调用和错误链路，便于定位问题和后续做评测。

### Q2：为什么混合检索用 RRF？

RRF 对不同召回通道（BM25 和向量）的分数尺度不敏感，融合稳定、实现简单，适合 MVP 快速落地。后续再引入重排器提升上限。

### Q3：软删除的价值是什么？

软删除可以降低误删风险，尤其是 URL 同步中出现 304、临时失败、数据源抖动时，不会立刻丢失知识。它更像“下线”而不是“抹掉”，有利于故障恢复和审计。

### Q4：你如何保证 RAG 结果可信？

目前通过检索事件可观测和来源记录保证可追踪，下一步会补齐回答级 citations，让每段答案都能回溯到具体 chunk/snippet。

---

## 10. 我自己的下一步（建议你每周更新）

- 本周：补 citations（回答携带来源片段）。
- 下周：接 reranker 做二阶段精排。
- 本月：做查询改写 + 检索评测基线。

> 维护建议：每完成一项功能，按“问题背景 -> 方案权衡 -> 落地细节 -> 结果指标 -> 复盘”追加 10~20 行，长期就是你的高质量面试素材库。
