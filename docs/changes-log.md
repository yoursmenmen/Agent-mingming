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

## 7) 2026-03-29 迭代（本地 Tool Calling 闭环）

### 后端：本地工具调用主链路
- `AgentOrchestrator` 在模型调用时注册本地工具：`TimeSkills`、`MathSkills`。
- 模型可在回答过程中触发 `now` / `add` 工具调用，而不是只走纯文本回答。
- 新增 `LocalToolProvider` 基础接口与 `ToolMetadata`：
  - 所有本地工具统一实现该接口
  - Orchestrator 通过 IOC 自动注入 `List<LocalToolProvider>` 并统一注册到模型
  - `ToolsController` 同样通过该列表自动返回工具目录（去掉硬编码）

### 后端：工具事件落库
- 新增 `ToolRunContextHolder`，在单次 run 内维护工具调用上下文（`runId + seq`）。
- 新增 `ToolEventService`，在工具执行时写入：
  - `TOOL_CALL`
  - `TOOL_RESULT`
- `TimeSkills`、`MathSkills` 已接入工具事件记录。

结果：时间线可回放“调用了什么工具、输入参数、输出结果”。

### 后端：工具清单接口
- 新增 `GET /api/tools`，返回当前可用本地工具（`now`、`add`）。

### 后端：高德天气工具接入（amap）
- 新增 `WeatherSkills`（`@Tool name = get_weather`），支持按城市名查询实时天气。
- 使用环境变量读取配置：
  - `AMAP_WEATHER_API_KEY`
  - `AMAP_WEATHER_BASE_URL`（可选，默认 `https://restapi.amap.com`）
- `WeatherSkills` 同样实现 `LocalToolProvider`，因此会被自动：
  - 注册到模型 tool calling 列表
  - 暴露到 `/api/tools` 前端工具清单

### 前端：展示可用工具
- 新增工具列表请求与状态：启动时拉取 `/api/tools`。
- 在 `RunStatusPanel` 展示“可用工具”清单，方便调试和学习。

### 新增/更新测试
- `ToolsControllerTest`：验证工具列表接口返回。
- `ToolEventServiceTest`：验证 `TOOL_CALL` 事件落库。
- 保持现有 orchestrator / session timeline 测试通过。

## 8) 2026-03-29 迭代（真流式输出 + 天气结构化结果）

### 后端：真流式输出（MODEL_DELTA）
- `AgentOrchestrator` 从 `.call().content()` 切换为 `ChatClient.stream().content()`。
- 每个增量 chunk 通过 SSE 立刻推送到前端（用于实时显示）。
- 数据库存储采用主流低成本策略：
  - 不持久化每个 delta chunk
  - 仅持久化最终 `MODEL_MESSAGE`（加上 `USER_MESSAGE` 与工具事件）

结果：前端不再“等全量后一次性返回”，而是实时字符/片段级输出。

### 修复：流式模式下工具事件缺失
- 原因：`TOOL_CALL/TOOL_RESULT` 记录依赖 `ThreadLocal`，在流式响应链路中上下文线程不稳定，导致工具事件偶发丢失。
- 修复：改为使用 Spring AI `ToolContext` 传递 `runId` 与 `seqCounter`，工具方法记录事件时直接从 `ToolContext` 读取上下文。

结果：即使在流式输出模式下，工具调用与结果也可稳定落库并在时间线回放。

### 后端：天气场景结构化输出
- 在 `MODEL_MESSAGE` payload 中新增可选 `structured` 字段。
- 当本轮检测到 `get_weather` 工具成功结果时，自动附带 `weather.v1` 结构化对象：
  - `city`、`weather`、`temperature`、`humidity`、`windDirection`、`windPower`、`reportTime`

结果：天气问答既有人类可读 `content`，也有机器可消费结构化数据。

### 前端：按 MODEL_DELTA 实时拼接 + 时间线分层
- 聊天区继续按 SSE `event` 的 `content` 实时拼接。
- 流式临时时间线项类型改为 `MODEL_DELTA`。
- 历史时间线以持久化事件为准（`MODEL_MESSAGE` + 工具事件）；`MODEL_DELTA` 仅作为流式临时态展示。

## 9) 2026-03-29 下班交接（当前状态与后续）

### 当前状态结论
- Tool Calling 主线已可用并闭环：
  - 本地工具注册、模型决策调用、工具事件落库、前端可观测。
- 高德天气工具已接入并纳入统一工具体系（`LocalToolProvider` + `/api/tools`）。
- 流式输出已改为真实 chunk 推送，前端支持增量拼接。
- 数据库存储采用主流策略：不存每个 delta，只存最终 `MODEL_MESSAGE` 与工具事件。

### 下一步计划（按优先级）
1. 结构化输出协议统一（从天气扩展到通用 schema + 前端结构化卡片渲染）。
2. RAG 最小闭环（先接入 `docs/` 文档知识库，加入检索事件可观测）。
3. 工具治理增强（超时、重试、错误分类与指标可视化）。

说明：用户已确认“显式 loop 方案不纳入当前计划”。
