# Harness Engineering 专题（Agent-MM）

> 目标：用工程化视角说明本项目如何把 LLM 包装进可控执行系统（Harness），并给出后续落地方向。

## 1. 什么是 Harness Engineering（在本项目语境）

在本项目里，Harness Engineering 指：

- 不把模型当作“自动完成器”，而是把模型放进一套可控执行框架；
- 关键链路可观测、关键动作可治理、关键决策可审计；
- 系统可在功能增长时保持稳定演进，而不是依赖 prompt 偶然性。

一句话：**让 Agent 从“会回答”升级为“可运营的软件系统”。**

---

## 2. 项目中已经落地的 Harness 能力地图

## 2.1 Input Harness（输入与会话编排）

- 请求入口：`POST /api/chat/stream`。
- 每轮对话分配 `runId`，多轮关联 `sessionId`。
- 价值：把一次生成拆成可追踪运行单元，支持 session 维度回放。

对应实现：

- `backend/src/main/java/com/mingming/agent/controller/ChatController.java`
- `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`

## 2.2 Retrieval Harness（检索编排与可观测）

- Hybrid 检索：BM25 + 向量并行召回，RRF 融合。
- 检索事件：记录 strategy、命中统计、来源样本。
- 价值：把“检索好不好”从主观感受变成可诊断信号。

对应实现：

- `backend/src/main/java/com/mingming/agent/rag/HybridRetrievalService.java`
- `backend/src/main/java/com/mingming/agent/rag/RetrievalEventService.java`

## 2.3 Tool Harness（工具接入与策略治理）

- 本地工具：Spring AI `@Tool`。
- 外部工具：MCP 运行时注入（动态 callback）。
- 治理：`allow-tools`、`deny-tools`、`max-callbacks`。
- 高风险门控：`run_local_command` 支持 hard-block / pending-confirm。
- 价值：控制 Agent“能做什么、做到哪一步需要人确认”。

对应实现：

- `backend/src/main/java/com/mingming/agent/mcp/McpRuntimeToolCallbackFactory.java`
- `backend/src/main/java/com/mingming/agent/mcp/McpToolService.java`
- `backend/src/main/java/com/mingming/agent/skill/McpBridgeSkills.java`

## 2.4 Human-in-the-loop Harness（人工确认闭环）

- 流程：`PENDING_CONFIRMATION -> confirm/reject -> 结果回写`。
- 结果事件：`MCP_CONFIRM_RESULT`。
- 价值：把高风险动作纳入人工控制，满足真实生产边界。

对应实现：

- `backend/src/main/java/com/mingming/agent/controller/McpController.java`
- `backend/src/main/java/com/mingming/agent/mcp/McpToolService.java`

## 2.5 Trace Harness（事件追踪与审计）

- 关键事件统一落库：`run_event`。
- 支持 run 级与 session 级查询。
- 价值：具备回放、排障、审计、后续评测基础。

对应实现：

- `backend/src/main/java/com/mingming/agent/event/RunEventType.java`
- `backend/src/main/java/com/mingming/agent/service/RunEventQueryService.java`
- `backend/src/main/java/com/mingming/agent/controller/RunsController.java`

## 2.6 Operator Harness（运行控制台）

- 前端有状态、RAG、时间线、工具面板。
- pending action 可直接确认/拒绝。
- 价值：系统状态可见、人工干预可执行。

对应实现：

- `frontend/src/composables/useChatConsole.ts`
- `frontend/src/components/TimelinePanel.vue`
- `frontend/src/components/RunStatusPanel.vue`

---

## 3. 当前成熟度评估（Harness 视角）

## 3.1 已成熟

- 主链路可跑：输入、检索、工具、事件、回放都已打通。
- 风险治理已上线：命令工具可拦截/可确认。
- 前端可操作：不只是展示结果，具备控制动作。

## 3.2 仍需补强

- 事件契约（Schema Contract）还未完全制度化。
- 指标面板仍偏“事件浏览”，缺少汇总指标。
- pending 生命周期有优化空间（状态收敛一致性）。

---

## 4. Harness-First 的实施原则（本项目后续）

1. **先立契约，再做功能**：新增事件先定义 schema，再写前后端。
2. **先保边界，再提能力**：工具能力扩展必须配套策略门与审计事件。
3. **先可观测，再做优化**：没有指标就不做“凭感觉优化”。
4. **先稳状态机，再美化界面**：前端先保证生命周期一致，再做视觉增强。

---

## 5. 事件契约最小基线（v1）

> 下面是当前阶段建议固定的最小契约，用于前后端协作与后续迭代。

| eventType | required fields | optional fields | 说明 |
|---|---|---|---|
| `TOOL_RESULT` | `tool`, `data.status`, `data.ok` | `data.actionId`, `data.error`, `data.server`, `data.tool` | 工具调用结果统一状态口径 |
| `MCP_CONFIRM_RESULT` | `actionId`, `status`, `server`, `tool`, `result` | `reason`, `source`, `result.error`, `result.exitCode` | 命令确认最终态 |
| `RETRIEVAL_RESULT` | `query`, `strategy`, `vectorHitCount`, `bm25HitCount`, `finalHitCount`, `hits` | `hitCount` | 检索质量分析入口 |

状态建议：

- `TOOL_RESULT.data.status`：`SUCCESS` / `FAILED` / `PENDING_CONFIRMATION` / `BLOCKED_POLICY` / `UNKNOWN`
- `MCP_CONFIRM_RESULT.status`：`CONFIRMED_EXECUTED` / `CONFIRM_EXECUTION_FAILED` / `REJECTED` / `UNKNOWN`

---

## 6. 下一阶段（Harness Engineering 主线）

按优先级推进：

1. 前端联调增强：pending/confirm 生命周期收敛。
2. 事件 schema 固化：契约文档 + 写入保护 + 前端降级策略。
3. 可观测指标：确认成功率、失败量、工具错误率可视化。

这三项完成后，项目会从“有能力的 Agent Demo”进入“可运营的 Agent Harness MVP”。

---

## 7. Contract-First 已落地（run-event-contracts + Registry）

当前已把 3 类核心事件收口到统一契约层：

- 合同目录：`backend/src/main/resources/run-event-contracts/`
  - `TOOL_RESULT.schema.json`
  - `MCP_CONFIRM_RESULT.schema.json`
  - `RETRIEVAL_RESULT.schema.json`
  - `MCP_TOOLS_BOUND.schema.json`
  - `RAG_SYNC.schema.json`
- 注册表：`backend/src/main/java/com/mingming/agent/event/contract/EventContractRegistry.java`
- 合同实现：
  - `ToolResultEventContract`
  - `McpConfirmResultEventContract`
  - `RetrievalResultEventContract`
  - `McpToolsBoundEventContract`
  - `RagSyncEventContract`

接入方式：

1. 业务代码先产出 raw payload；
2. 统一调用 `EventContractRegistry.normalizeAndValidate(...)`；
3. 再写入 `run_event`。

已接入写入点：

- `ToolEventService`（`TOOL_RESULT`）
- `McpToolService`（`MCP_CONFIRM_RESULT`）
- `RetrievalEventService`（`RETRIEVAL_RESULT`）
- `AgentOrchestrator.appendEvent`（`MCP_TOOLS_BOUND`）
- `RetrievalEventService.recordRagSync`（`RAG_SYNC`）

这一步的价值是把“散落在业务代码里的字段补齐逻辑”收敛成“项目级契约资产”，后续新增事件可复用同一模式。

另外，运行指标已增加 `contract_warning_total`，用于统计窗口内事件契约告警总量，便于观测 schema 漂移风险。
