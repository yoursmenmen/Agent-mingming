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

## 当前进度（2026-03-29 下班版）

### 已完成（核心链路）
- 会话复用：支持 `sessionId` 续聊，同会话多 run。
- 多轮记忆：按会话汇总历史 `USER_MESSAGE` / `MODEL_MESSAGE` 并做窗口裁剪。
- Tool Calling 主链路：模型可自动调用本地工具（`now`、`add`、`get_weather`）。
- 工具事件追踪：`TOOL_CALL` / `TOOL_RESULT` 会落库并可在时间线回放。
- 真流式输出：后端按 chunk SSE 推送，前端增量拼接显示。
- 持久化策略：采用主流方案，仅持久化最终 `MODEL_MESSAGE`（不存每个 delta chunk）。
- 天气结构化输出：天气问答在最终消息 payload 中带 `structured.weather.v1`。

### 已完成（前端体验）
- 侧栏拆分为：`状态` / `时间线` / `工具` 三面板。
- 工具面板独立展示 `/api/tools`，避免状态面板信息拥挤。
- 时间线按会话维度查看历史事件。

## 下一阶段计划（不含显式 loop）

1. **结构化输出标准化（优先）**
   - 将当前天气 `structured` 扩展为统一响应协议（如 `schema/version/type/data`）。
   - 前端按结构化字段做专用渲染卡片，而非仅文本。

2. **RAG 最小闭环**
   - 先用项目文档（`docs/`）作为知识源，接入检索增强回答。
   - 在时间线中增加检索事件（如命中片段摘要），便于学习可观测性。

3. **工具治理与可观测性增强**
   - 增加工具超时/失败分类与友好错误。
   - 补充关键指标（调用次数、失败率、平均耗时）展示到控制台。

4. **MCP 放到后续阶段**
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
