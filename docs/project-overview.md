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

## 这里的 harness engineering 落点是什么

在这个项目里，“harness engineering”更偏工程实践：

- 每次对话执行都会生成一个 **runId**
- 运行过程中所有关键动作都抽象为 **事件（event）**（模型输出、工具调用、错误等）
- 事件既会 **实时推送（SSE）**，也会 **持久化（PostgreSQL）**，从而支持：
  - 调试/定位（trace）
  - 回放（replay）
  - 未来的评测（eval）与观测（observability）

后续前端会把这些 event 渲染成时间线。
