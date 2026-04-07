# 单 Run Agent Loop 设计（方案 B）

## 1. 背景与目标

当前系统已具备以下能力：

- `POST /api/chat/stream` 的 SSE 流式输出
- run/event 落库与回放（`/api/runs/{runId}/events`）
- 本地工具调用、MCP 工具注入、RAG 检索

现状仍是“单次执行流”，缺少显式可治理的 loop 控制。为实现稳定可观测的 Agent 行为，本设计引入**单 run 内显式循环**，并保持前端与接口兼容。

本次目标：

1. 在单个 run 内实现模型-工具-模型的显式循环。
2. 引入统一终止策略：最多 8 轮、总时长 45 秒、连续工具失败 2 次终止。
3. 前端可解释 loop 进度与停止原因。
4. 代码边界为方案 B：新增 `AgentRunLoopService`，`AgentOrchestrator` 收敛为能力装配器。

非目标（本阶段不做）：

- 跨 run 工作流编排（暂停/恢复/取消/重试）
- 新增后台调度器或独立任务队列
- 修改聊天主接口协议（仍为 POST SSE）

## 2. 方案选择结论

已评估 A/B/C 三种路径，最终选择 **方案 B（独立 RunLoopService）**。

选择理由：

- 相比方案 A，`AgentOrchestrator` 不会持续膨胀，职责更清晰。
- 相比方案 C，避免“先快后乱”的控制流堆叠，降低二次返工概率。
- 未来升级跨 run 时，loop 策略与 step 执行逻辑可复用。

## 3. 架构设计

### 3.1 组件职责

- `ChatController`
  - 维持现有入口：创建 run、发送 `run` SSE 事件、触发执行。
  - 调用 `AgentRunLoopService.execute(...)`，并透传模型增量到前端。

- `AgentRunLoopService`（新增，核心）
  - 显式管理循环轮次、耗时、失败计数和停止判定。
  - 产出每轮事件、终止事件和最终报告。

- `AgentOrchestrator`（收敛）
  - 负责上下文能力：会话历史、RAG 检索、工具集合、最终 payload 组装、事件写入。
  - 不承载 loop 调度控制流。

### 3.2 执行流程（单 run）

1. `ChatController` 调用 `startRun` 创建 run。
2. `AgentRunLoopService` 初始化 `LoopState`。
3. 每轮执行：
   - 写入 `LOOP_TURN_STARTED`
   - 基于 `AgentOrchestrator` 组 prompt（history + retrieval context）
   - 调模型并处理工具调用
   - 写入 `LOOP_TURN_FINISHED`
   - 依据策略判定继续/终止
4. 终止时写入 `LOOP_TERMINATED`。
5. 写入最终 `MODEL_MESSAGE`（若需要兜底则写兜底文本）。

## 4. 核心数据结构与接口

### 4.1 新增类型

- `LoopTerminationPolicy`
  - `maxRounds = 8`
  - `maxDurationMs = 45000`
  - `maxConsecutiveToolFailures = 2`

- `LoopState`
  - `turnIndex`
  - `startedAt`
  - `elapsedMs`
  - `consecutiveToolFailures`
  - `latestAssistantText`
  - `terminated`

- `LoopStepResult`
  - `toolCallCount`
  - `toolFailureCount`
  - `assistantDelta`
  - `finalAnswerDetected`
  - `stepDurationMs`

- `LoopExecutionReport`
  - `totalTurns`
  - `terminationReason`
  - `terminationDetail`
  - `finalText`
  - `totalDurationMs`

### 4.2 新增服务接口（建议）

```java
public interface AgentRunLoopService {
    LoopExecutionReport execute(
        UUID runId,
        UUID sessionId,
        String userText,
        Consumer<String> sseConsumer
    );
}
```

## 5. 事件契约设计

### 5.1 新增事件类型

- `LOOP_TURN_STARTED`
- `LOOP_TURN_FINISHED`
- `LOOP_TERMINATED`

### 5.2 payload 约定

新增 loop 事件统一字段：

- `loopId`
- `turnIndex`
- `elapsedMs`

终止事件额外字段：

- `reason`：`FINAL_ANSWER | MAX_ROUNDS | TIMEOUT | CONSECUTIVE_TOOL_FAILURES | ERROR`
- `reasonDetail`
- `finalAnswerPresent`

兼容要求：

- 保留既有事件：`USER_MESSAGE`、`TOOL_CALL`、`TOOL_RESULT`、`MODEL_DELTA`、`MODEL_MESSAGE`
- 既有 API 响应结构不破坏，前端可增量识别新事件

## 6. 前端适配设计

### 6.1 目标

- 让用户明确看到“第几轮、为何停止”。
- 不破坏当前聊天输入/流式展示主路径。

### 6.2 改动点

- `frontend/src/services/eventMapper.ts`
  - 为 `LOOP_TURN_STARTED`、`LOOP_TURN_FINISHED`、`LOOP_TERMINATED` 增加摘要映射。

- `frontend/src/composables/useChatConsole.ts`
  - 聚合 loop 状态（当前轮次、终止原因、总耗时）供 UI 使用。

- `RunStatusPanel`（现有状态面板）
  - 增加 loop 状态卡片：`轮次 x/8`、`耗时`、`终止原因`。
  - 移动端仅展示核心信息，详细信息仍走时间线。

### 6.3 兼容性

- `ChatPanel` 的 `MODEL_DELTA` 拼接逻辑保持不变。
- 无 loop 事件时，页面行为保持当前版本。

## 7. 错误处理与兜底

- 若模型调用异常：
  - 记录 `ERROR` 与 `LOOP_TERMINATED(reason=ERROR)`
  - 回传用户可读错误说明

- 若连续工具失败达阈值：
  - 终止并输出“保护性停止”说明
  - 指明是工具连续失败触发，而非前端断流

- 若总时长超限：
  - 终止并提示“超时停止”

- 若达到轮次上限：
  - 终止并提示“达到本轮限制”

## 8. 测试与验收

### 8.1 后端测试

- 策略测试：`MAX_ROUNDS` / `TIMEOUT` / `CONSECUTIVE_TOOL_FAILURES` / `FINAL_ANSWER`
- 事件测试：
  - 每轮有开始和结束事件
  - 终止时有 `LOOP_TERMINATED`
  - run 内 `seq` 严格递增
- 接口兼容测试：`/api/chat/stream` 与 `/api/runs/{runId}/events` 行为不破坏

### 8.2 前端测试

- `eventMapper` 对新事件摘要映射稳定
- `useChatConsole` 在有无 loop 事件时均可正确渲染
- 移动端状态卡片不溢出

### 8.3 手工回归场景

1. 无工具问答：正常结束，`reason=FINAL_ANSWER`
2. 工具成功：1~3 轮内完成
3. 工具连续失败：第 2 次连续失败后终止
4. 超时场景：明确显示超时停止原因

## 9. 实施顺序（高层）

1. 后端新增 `AgentRunLoopService` 与 loop 相关模型。
2. `AgentOrchestrator` 提炼能力方法，移除新增控制流入口。
3. 增加 loop 事件类型与契约注册。
4. 前端事件映射与状态面板适配。
5. 测试补齐与回归验证。

## 10. 面向跨 run 的预留

虽然本期不做跨 run，但预留以下迁移点：

- 终止原因和 step 结果结构保持稳定，可迁移到持久化状态机。
- `LoopTerminationPolicy` 独立，后续可按任务类型差异化配置。
- `LoopExecutionReport` 可作为未来“继续执行/重试”的基础上下文对象。
