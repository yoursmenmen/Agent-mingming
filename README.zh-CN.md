# Agent_mm（AI Agent Monorepo MVP）

这是一个用于自建 AI Agent 工程化项目的 **单仓库（monorepo）MVP**：

- 后端：**Spring Boot + Spring AI Alibaba（通义/百炼 / DashScope）**
- 前端：**Vue 3 + Vite**
- 流式输出：**SSE**（Server-Sent Events）
- 存储：**PostgreSQL**（run + event trace 落库）
- 鉴权：**简单 Token**（`Authorization: Bearer <token>`）

> English version: **README.md**.

## 当前支持的功能

后端：
- 健康检查：`GET /health`
- SSE 对话：`POST /api/chat/stream`（返回 SSE 事件流）
- Trace 落库：PostgreSQL + Flyway migrations
- 查询历史事件：`GET /api/runs/{runId}/events`
- 简单鉴权：除 `/health` 与 `/actuator/**` 外，均要求 `Authorization: Bearer <token>`
- Skills/Tools：demo `now`、`add`（基于 `@Tool`）
- RAG（docs）：
  - markdown 分片 + BM25 检索
  - pgvector 向量检索 + 混合召回（vector + BM25）
  - 通过 `RETRIEVAL_RESULT` 事件做检索可观测
- MCP：配置读取 + API 骨架（`/api/mcp/servers`、`/api/mcp/tools` 目前是占位）

前端：
- Vue3/Vite TypeScript 脚手架（UI 连接后端的部分后续完善）

文档：
- 见 `docs/README.md`

## 运行前准备

- JDK 21（你本机路径：`C:\Env\Java\Java21`）
- Maven 3.9+
- Node 20+
- Docker（通过 WSL 使用）

## 启动 PostgreSQL

在仓库根目录执行（建议在 **WSL** 里执行，因为你那边 docker 可用）：

```bash
cd /c/DevApp/MyResp/MyJavaProject/Agent_mm
docker compose up -d
```

`docker-compose.yml` 默认数据库配置：
- DB：`agentdb`
- user：`agent`
- password：`agent`
- port：`5432`

## 启动后端

在仓库根目录：

```bash
export JAVA_HOME="/c/Env/Java/Java21"
export PATH="$JAVA_HOME/bin:$PATH"

cd backend

# 必填
export AI_DASHSCOPE_API_KEY="<你的key>"
export AGENT_API_TOKEN="dev-token-change-me"

# 向量 RAG（IDEA 从项目根目录运行时推荐）
export AGENT_RAG_VECTOR_ENABLED="true"
export AGENT_RAG_VECTOR_DOCS_ROOT="docs"
export AGENT_RAG_VECTOR_EMBEDDING_MODEL="text-embedding-v3"
export AGENT_RAG_VECTOR_EMBEDDING_VERSION="2026-03"

# 可选：覆盖数据库连接（默认就是 localhost:5432/agentdb）
# export DB_URL="jdbc:postgresql://localhost:5432/agentdb"
# export DB_USER="agent"
# export DB_PASSWORD="agent"

mvn spring-boot:run
```

后端地址：`http://localhost:18080`

## 验证 SSE 对话接口

示例（curl）：

```bash
curl -N \
  -H "Authorization: Bearer dev-token-change-me" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:18080/api/chat/stream" \
  -d '{"message":"hello"}'
```

你会看到类似事件：
- `event: run`：包含 `runId`
- `event: event`：包含模型输出 payload

然后可查询落库事件：

```bash
curl \
  -H "Authorization: Bearer dev-token-change-me" \
  "http://localhost:18080/api/runs/<runId>/events"
```

## 启动前端（开发模式）

```bash
cd frontend
npm install
npm run dev
```

## 备注

- 当前 SSE 接口是 **POST /api/chat/stream**。浏览器原生 `EventSource` **只支持 GET**。
  - 前端会采用 `fetch()` + ReadableStream 读取并解析 SSE（推荐）
  - 或者后端改造为 GET（需要重新设计 message 的传参方式）
- 向量索引同步在启动后异步执行；如果 `docsRoot` 配错，`doc_chunk`/`doc_chunk_embedding` 会保持为空。
- `AGENT_RAG_VECTOR_DOCS_ROOT` 是相对进程工作目录解析：IDEA 在项目根运行建议 `docs`；在 backend 目录运行建议 `../docs`。

## 文档入口

- `docs/README.md`
- `docs/project-overview.md`
- `docs/changes-log.md`
- `docs/config-reference.md`
