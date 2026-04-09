# Full Agent Loop 设计（方案 B：TurnExecutionService）

## 1. 目标

将当前 `executeSingleTurn(maxRounds=1)` 的阶段实现升级为**完整单 run 多轮 agent loop**：

- 最多 8 轮（`maxRounds=8`）
- 总时长上限 45 秒（`maxDurationMs=45000`）
- 连续工具失败 2 次终止（`maxConsecutiveToolFailures=2`）
- 继续/停止判定由 `LoopStepResult` 明确返回（不做事件推断）

## 2. 核心设计结论

采用方案 B：新增 `TurnExecutionService`，把“单轮执行”与“循环控制”解耦。

- `AgentRunLoopService`：只负责 loop 调度与终止策略。
- `TurnExecutionService`：只负责执行 1 轮并返回 `LoopStepResult`。
- `AgentOrchestrator`：只负责 run/session 初始化、事件桥接、依赖装配。

## 3. 职责边界

### 3.1 AgentOrchestrator

- 保留：`startRun`、`appendEvent`、`executeLoop`（桥接 loop 事件到 run_event）、SSE 数据透传。
- 调整：`executeSingleTurn` 升级为 `executeAgentLoop`（语义改为完整 loop 入口）。
- 不再承担：单轮业务细节（RAG+模型+工具）具体执行。

### 3.2 AgentRunLoopService

- 输入：`LoopTerminationPolicy`、`LoopTurnExecutor`、`LoopEventListener`
- 输出：`LoopExecutionReport`
- 保持：`LOOP_TURN_STARTED / LOOP_TURN_FINISHED / LOOP_TERMINATED` 生命周期事件。

### 3.3 TurnExecutionService（新增）

- 输入：`TurnContext`
- 输出：`LoopStepResult`
- 单轮职责：
  1) 构建 prompt（历史 + 检索）
  2) 调模型并处理工具调用
  3) 计算本轮状态并返回 `LoopStepResult`

## 4. 数据结构与接口

## 4.1 TurnContext（新增）

建议字段：

- `UUID runId`
- `UUID sessionId`
- `String userText`
- `int turnIndex`
- `AtomicInteger seq`
- `Consumer<String> sseDataConsumer`

### 4.2 LoopStepResult（扩展）

在现有 `finalAnswerReady/toolFailure` 基础上扩展：

- `boolean finalAnswerReady`
- `boolean toolFailure`
- `int toolCallCount`
- `String assistantContent`（可空）
- `Map<String, Object> meta`（可空，诊断信息）

### 4.3 TurnExecutionService 接口（新增）

```java
public interface TurnExecutionService {
    LoopStepResult executeTurn(TurnContext context);
}
```

## 5. 控制流

1. `ChatController` 调用 orchestrator 的完整 loop 入口。
2. orchestrator 创建 `policy(8, 45000, 2)`。
3. orchestrator 调 `executeLoop(..., turnExecutor)`。
4. `turnExecutor` 内部调用 `turnExecutionService.executeTurn(context)`。
5. `AgentRunLoopService` 根据 `LoopStepResult + policy` 决定继续或终止。
6. loop 事件通过 `LoopEventListener` 回调到 orchestrator，统一落库。
7. 结束后返回 `LoopExecutionReport`，SSE 正常完成。

## 6. 终止规则优先级

按当前策略与状态机保持一致：

1. `FINAL_ANSWER`（来自 `LoopStepResult.finalAnswerReady=true`）
2. `MAX_ROUNDS`
3. `TIMEOUT`
4. `CONSECUTIVE_TOOL_FAILURES`

说明：终止原因以 loop 引擎统一判定，避免入口代码分叉判定。

## 7. 事件与前端契约

- loop 事件字段口径固定为：`turnIndex`、`elapsedMs`、`reason`（terminated）。
- 前端继续由：
  - `frontend/src/services/eventMapper.ts`
  - `frontend/src/composables/useChatConsole.ts`
  - `frontend/src/components/RunStatusPanel.vue`
  消费并展示。

## 8. 测试策略

### 后端

1. `DefaultAgentRunLoopServiceTest`
   - 8轮上限
   - 45秒超时
   - 连续失败2次
   - final answer 优先

2. `AgentOrchestratorTest`
   - 主入口调用完整 loop（非 maxRounds=1）
   - LOOP_* 事件真实落库
   - seq 连续递增

3. `ChatControllerTest`
   - 委派到完整 loop 入口
   - SSE 行为兼容

### 前端

1. `eventMapper.test.ts`
   - loop 事件摘要字段口径一致（turnIndex/elapsedMs/reason）

2. `useChatConsole.loop.test.ts`
   - 多轮事件下状态聚合正确
   - 终止原因映射正确

## 9. 风险与约束

- 风险 1：单轮执行拆分后，`AgentOrchestrator` 依赖注入和测试桩可能失配。
  - 缓解：先补测试再拆分，分步迁移。

- 风险 2：工具失败定义不一致（业务失败 vs 可重试失败）。
  - 缓解：在 `LoopStepResult.toolFailure` 中先用保守定义，后续可再细分。

- 风险 3：多轮下 token/延迟增长。
  - 缓解：保留历史窗口裁剪，必要时增加 turn 内上下文压缩。

## 10. 分阶段实施建议

1. 新增 `TurnExecutionService` 与 `TurnContext`，补测试。
2. 将当前单轮逻辑迁移到 `executeTurn`。
3. 将主入口策略改为 `8/45000/2`，去掉 `maxRounds=1` 特化。
4. 回归后端/前端测试并做一次手工联调。

## 11. 验收标准

- 主入口在复杂问题下可执行 2~8 轮。
- 达到任一终止条件时，终止原因正确且可回放。
- 前端状态面板展示当前轮次/耗时/终止原因。
- `mvn test`、`npm run test:unit`、`npm run build` 全通过。
