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

## 10) 2026-03-30 迭代（结构化卡片视觉升级）

- 前端 `frontend/src/style.css` 为结构化卡片引入 sakura soft-glass 视觉系统：
  - 新增结构化卡片专用设计变量（玻璃背景、边框、强调光晕）。
  - 统一卡片容器、标题区、内容区与状态态（streaming）样式，提升对比度与可读性。
  - 增加 `structured-card-enter` 入场动画与移动端栅格降级规则，保证手机端信息不拥挤。
- 新增 `frontend/src/components/structured/style-smoke.test.ts`：对关键 token、核心 class hook、动画与移动端规则做样式冒烟校验，防止后续样式回归。

## 11) 2026-03-30 迭代（Docs RAG BM25 + 检索事件可观测）

### 后端：docs 分块与 BM25 检索
- 新增 `com.mingming.agent.rag.DocsChunk` 作为统一 chunk 数据结构。
- 新增 `DocsChunkingService`：
  - 扫描 `docs/` 下 Markdown 文件并按相对路径稳定排序。
  - 按标题层级生成 `headingPath`，按段落切分并做短段合并/长段拆分。
  - 基于 `docPath|headingPath|offset` 生成稳定 `chunkId`。
- 新增 `Bm25RetrieverService`：
  - 提供 `search/retrieve`，支持 `topK` 和阈值过滤。
  - 词元策略覆盖英文数字 token 与中文 bigram。

### 后端：检索事件落库并接入编排
- `RunEventType` 新增 `RETRIEVAL_RESULT`。
- 新增 `RetrievalEventService`：将检索结果写入 `run_event`，payload 包含：
  - `query`
  - `hitCount`
  - `hits`（`chunkId/docPath/headingPath/snippet/score`）
- `AgentOrchestrator.runOnce(...)` 增加链路：
  - `load docs chunks -> BM25 检索 -> 记录 RETRIEVAL_RESULT`
  - 将召回内容注入 prompt（“检索参考资料”）后再发起模型调用
  - docs 不可用/检索异常时自动降级为空检索，不中断回答主流程

### 前端：时间线检索事件可读摘要
- 更新 `frontend/src/services/eventMapper.ts`：
  - 针对 `RETRIEVAL_RESULT` 输出人类可读摘要（命中数、查询词、代表文档）。
  - 无命中时明确展示 `未命中` 与 `N/A` 代表文档。
- 新增 `frontend/src/services/eventMapper.test.ts` 覆盖命中/未命中摘要分支。

### 测试与夹具
- 新增后端测试：
  - `DocsChunkingServiceTest`
  - `Bm25RetrieverServiceTest`
  - `RetrievalEventServiceTest`
- 更新 `AgentOrchestratorTest`，补充检索事件 seq 与 prompt 注入断言。
- 新增 `docs/rag-fixtures/` 夹具文档（`architecture-long.md`、`release-notes.md`、`noise.md`）用于检索与展示场景验证。

## 12) 2026-03-30 迭代（Vector Stage Task 5：启动异步同步）

### 启动阶段异步同步组件
- 新增 `backend/src/main/java/com/mingming/agent/rag/VectorRagBootstrapSync.java`：
  - 监听 `ApplicationReadyEvent`。
  - 在 `agent.rag.vector.enabled=true` 时，通过 `TaskExecutor` 异步执行向量分块同步。
  - 记录同步完成统计日志（inserted/updated/softDeleted/unchanged）。
  - 同步异常仅告警，不中断应用启动流程。

### 测试覆盖补齐
- 更新 `backend/src/test/java/com/mingming/agent/rag/VectorRagBootstrapSyncTest.java`：
  - 保留启用/禁用分支验证。
  - 新增异常分支验证：后台同步抛错时不向外传播异常，且同步调用仍被触发。

### 实施报告
- 新增 `docs/superpowers/reports/2026-03-30-vector-rag-implementation-report.md`，记录 Task 5 实施内容与全量验证结果摘要。

## 13) 2026-03-31 迭代（Vector RAG 主链路完成 + 维度对齐）

### 向量检索与混合召回主链路
- 新增向量持久化模型与迁移：`doc_chunk` / `doc_chunk_embedding`（pgvector）。
- 新增增量同步服务：按 `content_hash + embedding_model + embedding_version` 判定更新，执行 upsert + 软删除。
- 新增 `VectorRetrieverService` 与 `HybridRetrievalService`：支持向量召回 + BM25 融合（RRF）及异常降级。
- `AgentOrchestrator` 接入 hybrid 检索，检索事件扩展为可观测元数据。

### 可观测性与前端时间线
- `RETRIEVAL_RESULT` 事件新增 `strategy/vectorHitCount/bm25HitCount/finalHitCount`。
- 前端 `eventMapper` 增加混合检索摘要展示，并兼容旧 payload。

### 真实 Embedding 接入与维度修复
- 向量生成从“伪向量”切换为 Spring AI `EmbeddingModel.embed(...)`。
- 发现 DashScope 当前返回 1024 维后，新增迁移 `V4__vector_dimension_1024.sql`：
  - 将向量列改为 `vector(1024)`
  - 清理旧维度 embedding 并重建向量索引
- 同步更新检索与同步服务的维度校验及测试断言。

## 14) 2026-04-02 迭代（MCP 动态接入、确认网关与前端可观测）

### MCP 动态工具注入主链路
- `AgentOrchestrator` 支持在每次对话前动态发现 MCP tools，并以 runtime callback 注入模型工具列表。
- 新增 `McpRuntimeToolCallbackFactory`：
  - 从 MCP `tools/list` 构建 callback
  - 支持工具命名规整与去重
  - 支持 `inputSchema.required` 提取并做基础必填校验
- 新增 run event `MCP_TOOLS_BOUND`，记录本次注入/阻断/发现错误详情。

### MCP 运行时治理与日志
- 新增配置：
  - `agent.mcp.runtime.enabled`
  - `agent.mcp.runtime.allow-tools`
  - `agent.mcp.runtime.deny-tools`
  - `agent.mcp.runtime.max-callbacks`
- MCP 日志从“高频列表噪音”转向“关键调用日志”，包含 source/server/tool/args 摘要与耗时。

### run_local_command 二次确认网关
- 在 `McpToolService` 增加命令风险分级：
  - 硬拦截：明显破坏性命令模式（如 `rm *`）
  - 待确认：安装或变更类命令
- 新增待确认动作接口：
  - `GET /api/mcp/actions/pending`
  - `POST /api/mcp/actions/{actionId}/confirm`
  - `POST /api/mcp/actions/{actionId}/reject`
- confirm 执行失败时改为业务化返回，避免 servlet 直接抛 500。

### 前端交互与体验调整
- 时间线改为“关键事件实时可见”，并过滤 `MODEL_DELTA`。
- `TOOL_CALL` / `TOOL_RESULT` 摘要增强，支持展示 pending/block 状态。
- 待确认动作入口改到聊天区，支持一键确认/拒绝。
- 确认后自动触发一次 follow-up 对话，将命令执行结果喂给模型并输出成功/失败说明。
- 修复 `TimelinePanel` props 空值导致的渲染异常（`includes` of undefined）。

### 本地 MCP 兼容性修复（Windows）
- `tools/mcp/local_ops_mcp.py` 对 `npm/pnpm/yarn/npx/node` 增加 `.cmd` 兜底，减少 `[WinError 2]`。

### 文档同步
- 更新：`docs/config-reference.md`、`docs/mcp-local-ops-guide.md`、`docs/README.md`
- 新增：`docs/progress-status.md`（当前阶段快照与已知问题/下一步）

## 15) 2026-04-11 迭代（ReAct Agent Loop 完成 + 文档对齐）

### 后端：显式 ReAct Loop 主链路落地
- `ChatController` 已将聊天入口切换到 `ReactAgentService.execute(...)`，在同一次 run 内进行“模型输出 -> 工具调用 -> 工具结果回填 -> 下一轮”循环。
- `ReactAgentService` 增加终止策略保护：
  - `maxTurns`
  - `maxDurationMs`
  - `maxConsecutiveErrors`
- `ToolDispatcher` 负责统一执行 AgentTool：工具路由、参数解析、确认网关、异常兜底。

### 后端：AgentTool 与确认网关
- 当前 AgentTool 集合：`fetch_page`、`file_op`、`shell_exec`（通过 `AgentToolConfig` 注册）。
- 高风险动作确认入口：`POST /api/runs/{runId}/tool-confirm`。
- 工具执行结果继续落库为 `TOOL_CALL/TOOL_RESULT`，可在时间线回放。

### 文档同步（本次）
- 更新 `README.md`：补充 ReAct loop、AgentTool、tool-confirm 使用示例。
- 更新 `README.zh-CN.md`：补充 ReAct loop、AgentTool、tool-confirm 使用示例。
- 更新 `docs/project-overview.md`：将阶段描述改为“Agent Loop 已落地”，并补充下一阶段 Summary Memory 规划。
- 更新 `docs/progress-status.md`：重写为 2026-04-11 现状快照，明确“会话窗口已做、摘要记忆未做”的上下文管理现状。

## 16) 2026-04-11 迭代（Summary Memory + Prompt 组装升级）

### 上下文治理：运行时滑动窗口
- 新增 `ContextWindowPolicy` 与 `ContextWindowManager`。
- `ReactAgentService` 在每轮工具回填后执行窗口裁剪，控制消息数量与字符预算。
- 保留 system/summary 前缀，仅滑动裁剪后续消息窗口。

### 会话记忆：可持久化 SESSION_SUMMARY
- `RunEventType` 新增 `SESSION_SUMMARY`。
- `RunEventRepository` 新增 `findLatestSessionSummaryEvent(sessionId)`，按会话获取最新摘要。
- 新增 `SessionSummaryService`：
  - 读取会话最新摘要
  - 在 run 结束时基于旧摘要 + 新对话片段生成新摘要
  - 落库摘要事件并写入 `sessionId/sourceRunId/turnCount/content`

### Prompt 组装策略升级
- `AgentOrchestrator` 新增 `buildSessionHistoryMessages(sessionId)`（仅历史，不拼当前 user）。
- `ReactAgentService` 初始 prompt 改为：
  - `BASE_SYSTEM_PROMPT`
  - 可选 summary system message
  - recent history window
  - current UserMessage
- 这使 ReAct 主链路对齐策略：`system + summary + recent window + 当前问题`。

### 测试补充
- 新增：`ContextWindowManagerTest`
- 新增：`SessionSummaryServiceTest`
- 新增：`ReactAgentServiceTest`
- 回归通过：`AgentOrchestratorTest`

## 17) 2026-04-13 迭代（时间线轮次与交互修复）

### 后端：Summary 轮次累计修复
- `SESSION_SUMMARY.turnCount` 从“本次 run 轮次”修正为“会话累计轮次”。
- `SessionSummaryService` 在刷新摘要时读取上一次摘要 `turnCount` 并累加。

### 前端：时间线流式阶段会话视图修复
- run 进行中轮询由 `fetchRunEvents(runId)` 调整为优先 `fetchSessionEvents(sessionId)`。
- 修复“第三轮流式过程中只看到当前 run，历史轮次暂时消失”的问题。

### 前端：MODEL_OUTPUT 展示修复
- `MODEL_OUTPUT` 时间线文案改为“会话轮次 + 本次 run 推理次数”。
- 示例：`🧠 第 2 轮对话 · 本次第 1 次推理：...`。

### 前端：时间线折叠头交互增强
- 轮次折叠头改为 sticky，滚动浏览事件时可直接在顶部折叠当前轮次。

### 前端：测试补充
- 更新 `eventMapper` 单测，覆盖 `SESSION_SUMMARY` 文案。
- 新增 `TimelinePanel` 视图测试，覆盖 `MODEL_OUTPUT` 会话轮次展示。
