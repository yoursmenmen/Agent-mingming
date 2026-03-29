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

## 6) 2026-03-29 迭代（会话复用、时间线、后端分层）

### 会话复用 + 事件落库增强
- `ChatController` 请求体新增可选 `sessionId`，并在 `event: run` 中回传 `sessionId` + `runId`。
- `AgentOrchestrator.startRun(...)` 支持：
  - 未提供 `sessionId` 时新建 `chat_session`
  - 提供 `sessionId` 时复用该会话并新建 `agent_run`
- `RunEventType` 新增 `USER_MESSAGE`，并在模型调用前先落用户消息事件。

结果：同一会话可产生多次 run；每次 run 内 `seq` 仍从 1 递增，但跨 run 不混淆。

### 时间线升级为会话维度
- 后端新增接口：`GET /api/sessions/{sessionId}/events`
- 前端优先按 session 拉取事件（否则回退按 run），并在历史拉取成功后清空临时流式项，避免重复显示。

结果：时间线不再每轮输入都清空，也不会出现同一条模型消息在 user 前后重复。

### 后端分层重构（Controller 瘦身）
- 新增 `RunEventQueryService`，将 run/session 事件查询与映射逻辑从 `RunsController` 下沉到 service。
- `RunsController` 仅保留 HTTP 路由与参数绑定。

结果：控制器职责更清晰，后续扩展过滤、分页、权限检查时更容易维护。

### 多轮上下文记忆（进行中）
- `AgentOrchestrator` 新增历史消息装配逻辑：
  - 按 `sessionId` 汇总历史 `USER_MESSAGE` 与 `MODEL_MESSAGE`
  - 组装为模型输入消息序列，再拼接当前用户输入
- 增加上下文窗口保护：
  - `MAX_CONTEXT_MESSAGES = 20`
  - `MAX_CONTEXT_CHARS = 12000`
- 优化历史查询性能：
  - `runOnce` 直接使用后端已确认的 `sessionId`，不再额外通过 `runId` 反查
  - 新增 `run_event` + `agent_run` 联表限量查询，只取最近会话对话事件（`USER_MESSAGE`/`MODEL_MESSAGE`）

结果：模型在后续轮次可读取前文，同时避免上下文无限增长。

### 新增/更新测试
- `AgentOrchestratorTest`
  - 验证用户消息与模型消息落库顺序与 `seq`
  - 验证 session 复用与 session 不存在异常
  - 验证历史消息装配与上下文窗口限制
- `RunsControllerTest`
  - 验证 controller 对 service 的委托
- `RunEventQueryServiceTest`
  - 验证会话内跨 run 事件聚合与空会话处理
