# 项目概览（Project Overview）

本仓库是一个 **monorepo** 的 MVP，用于搭建一个可落地的 AI Agent 工程化系统：

- **后端**：Spring Boot 3.x + Spring AI Alibaba（DashScope / 百炼）
- **前端**：Vue 3 + Vite（TypeScript）
- **流式输出**：SSE（Server-Sent Events）
- **存储**：PostgreSQL（run + event trace 落库）
- **安全**：简单 Token 鉴权（`Authorization: Bearer <token>`）

## 仓库结构

```
Agent_mm/
  backend/               # Spring Boot 服务
  frontend/              # Vue3 控制台 UI
  docs/                  # 文档
  docker-compose.yml     # 本地 PostgreSQL
```

## 目前能做什么（MVP）

1. 用 Docker Compose 启动 PostgreSQL。
2. 启动后端（端口 **18080**）。
3. 调用 **`POST /api/chat/stream`** 获取 SSE 事件流（包含 runId 与模型输出事件）。
4. 通过 **`GET /api/runs/{runId}/events`** 查询已落库的 run events，用于回放与排查。

## 当前进度（2026-04-11 Agent Loop 已落地）

### 已完成（核心链路）
- 会话复用：支持 `sessionId` 续聊，同会话多 run。
- 多轮记忆（会话级）：按会话汇总历史 `USER_MESSAGE` / `MODEL_MESSAGE` 并做窗口裁剪（`MAX_CONTEXT_MESSAGES` + `MAX_CONTEXT_CHARS`）。
- ReAct Agent Loop：`POST /api/chat/stream` 已切到显式 loop（模型输出 -> tool call -> tool result -> 下一轮）。
- 运行时上下文窗口：loop 内按消息数 + 字符预算滑动裁剪，抑制单次 run 上下文膨胀。
- Summary Memory：新增会话级摘要记忆，历史会话压缩为 `SESSION_SUMMARY` 并在后续请求注入。
- Prompt 组装策略：已统一为 `system + summary + recent window + 当前问题`。
- Tool Calling 主链路：模型可自动调用 AgentTool（`fetch_page`、`file_op`、`shell_exec`）并由服务端分级执行。
- 工具事件追踪：`TOOL_CALL` / `TOOL_RESULT` 会落库并可在时间线回放。
- 终止策略：支持最大轮次、最大时长、连续失败次数上限，防止单次 run 失控。
- 工具确认网关：高风险工具调用进入确认态，前端通过 `POST /api/runs/{runId}/tool-confirm` 回传审批。
- 真流式输出：后端按 chunk SSE 推送，前端增量拼接显示。
- 持久化策略：采用主流方案，仅持久化最终 `MODEL_MESSAGE`（不存每个 delta chunk）。
- 天气结构化输出：天气问答在最终消息 payload 中带 `structured.weather.v1`。

### 已完成（前端体验）
- 侧栏拆分为：`状态` / `时间线` / `工具` 三面板。
- 工具面板独立展示 `/api/tools`，避免状态面板信息拥挤。
- 时间线按会话维度查看历史事件。

### 已完成（RAG 能力）
- docs 检索增强：`docs/**/*.md` 分片并接入 BM25 检索。
- 向量检索：基于 PostgreSQL `pgvector` 存储 embedding。
- 混合召回：支持 vector + BM25 融合并输出最终 topN。
- 增量同步：按 `content_hash + embedding_model + embedding_version` 判定更新，采用 upsert + 软删除。
- 非阻塞启动：向量同步在应用启动后后台异步执行。
- 可观测性：`RETRIEVAL_RESULT` 事件包含 `strategy/vectorHitCount/bm25HitCount/finalHitCount` 与命中来源。
- 同步状态接口：新增 `GET /api/rag/sync/status` 与 `POST /api/rag/sync/trigger`。
- 同步生命周期：状态流转为 `running -> completed/failed`，并输出 `RAG_SYNC` 生命周期事件（started/completed/failed）。
- 多知识源接入：新增 URL source ingestion，可将外部 URL 内容纳入同一 chunk + embedding + hybrid 检索链路。
- source 维度扩展：`doc_chunk` 新增 `source_type/source_id` 字段，支持 `local_docs|url` 源区分。

## 下一阶段计划

1. **向量 RAG 稳定化**
   - 完成真实 embedding 模型维度对齐与迁移治理（当前按 1024 维）。
   - 加强 docsRoot 诊断与同步状态可视化，降低“库为空无报错”排查成本。

2. **多知识源接入**
   - 在 docs 之外继续扩展外部文档源（URL 已接入，后续可接目录/API）。
   - 按 source 维度做召回分层、过滤与去重。

3. **工具治理与可观测性增强（继续）**
   - 增加工具超时/失败分类与友好错误。
   - 补充关键指标（调用次数、失败率、平均耗时）展示到控制台。

4. **记忆质量优化**
   - 优化摘要刷新频率（每次 run vs 每 N 次 run）与摘要长度预算。
   - 增加摘要质量评测（事实保留率、目标一致性、压缩率）。

5. **MCP 放到后续阶段**
   - 当前以本地 tool + RAG 打牢学习主线，MCP 作为标准化接入扩展再推进。

## 这里的 harness engineering 落点是什么

在这个项目里，“harness engineering”更偏工程实践：

- 每次对话执行都会生成一个 **runId**
- 运行过程中所有关键动作都抽象为 **事件（event）**（模型输出、工具调用、错误等）
- 事件既会 **实时推送（SSE）**，也会 **持久化（PostgreSQL）**，从而支持：
  - 调试/定位（trace）
  - 回放（replay）
  - 未来的评测（eval）与观测（observability）

后续前端会把这些 event 渲染成时间线。
