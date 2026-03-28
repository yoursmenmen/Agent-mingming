# 变更记录（Claude 做了什么）

这份文件记录本仓库当前已创建的内容，以及每项的作用与理由。

## 1) Monorepo 脚手架

创建目录：
- `backend/`：Spring Boot 后端
- `frontend/`：Vue3/Vite 前端
- `docs/`：文档

创建：
- `.gitignore`：忽略 `backend/target`、`frontend/node_modules`、IDE 文件、`.env`

## 2) 后端（Spring Boot）

关键文件：
- `backend/pom.xml`
  - Spring Boot 3.3.5
  - Java 21
  - 主要依赖：
    - `spring-boot-starter-web`：REST + SSE
    - `spring-boot-starter-actuator`：健康检查
    - `spring-boot-starter-data-jpa` + `postgresql`：持久化
    - `flyway-core`：数据库迁移
    - `spring-ai-alibaba-starter-dashscope`：LLM（通义/百炼）接入
    - `spring-boot-starter-webflux`：用于 MCP HTTP 客户端（`WebClient`）
    - `jackson-dataformat-yaml`：读取 `mcp/servers.yml`

- `backend/src/main/resources/application.yml`
  - 服务端口：`18080`
  - DB 环境变量：`DB_URL/DB_USER/DB_PASSWORD`
  - DashScope Key：`AI_DASHSCOPE_API_KEY`
  - API Token：`AGENT_API_TOKEN`

### API Token 鉴权
- `com.mingming.agent.security.ApiTokenFilter`
  - 默认拦截所有 API
  - 放行：`/health` 与 `/actuator/**`
  - 要求请求头：`Authorization: Bearer <token>`

### Skills（Function Calling / Tools）
- `com.mingming.agent.skills.TimeSkills`
  - `@Tool now`：返回当前 UTC 时间
- `com.mingming.agent.skills.MathSkills`
  - `@Tool add`：两数相加

> 说明：目前 demo tool 已注册为 `@Tool`，下一步会把“模型触发 tool call -> 执行 tool -> 回写 tool result”这一链路做得更完整。

### Run Trace 落库
Flyway migrations：
- `V1__baseline.sql`：占位
- `V2__run_storage.sql`：创建表
  - `chat_session`
  - `agent_run`
  - `run_event`（JSONB payload）

### SSE 对话接口
- `POST /api/chat/stream`
  - 返回 `SseEmitter`
  - 先发一个 `run` 事件，包含 `runId`
  - 再发 `event` 事件，包含模型输出 payload

### 查询历史 run events
- `GET /api/runs/{runId}/events`
  - 按 `seq` 排序返回落库事件

## 3) MCP（骨架）

- `backend/src/main/resources/mcp/servers.yml`：MCP server 配置占位（当前 `servers: []`）
- `McpServerRegistry`：读取 YAML
- `McpController`：
  - `GET /api/mcp/servers`
  - `GET /api/mcp/tools`（当前返回空列表，占位）

## 4) 前端

- 使用 Vite 的 Vue TS 模板初始化

> 说明：目前是脚手架状态，下一步会实现 Chat + Trace 页面并对接后端 SSE。

## 5) 本地 PostgreSQL

- `docker-compose.yml`：启动 `postgres:16`，默认 db/user/password = `agent`

## 已知约束 / 下一步

- 浏览器原生 `EventSource` **只支持 GET**；当前后端 SSE 使用 **POST**。
  - 后续前端会用 `fetch()` + ReadableStream 自己解析 SSE（推荐）
  - 或者把后端改为 GET（message 走 query/body 方案需要再设计）
- DashScope 的“真正 token 级流式输出”与“结构化 tool calls”还会继续增强（按 MVP 逐步完善）。
