# Single-Run Agent Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在单个 run 内实现可治理的 agent loop（最多 8 轮、45 秒超时、连续工具失败 2 次终止），并完成前端可观测适配。

**Architecture:** 新增 `AgentRunLoopService` 作为循环调度核心，`AgentOrchestrator` 收敛为上下文和能力装配器。通过新增 loop 事件类型和事件契约，将轮次状态与终止原因纳入现有 `run_event` 回放链路。前端基于 `eventMapper + useChatConsole + RunStatusPanel` 增量适配，不破坏当前聊天流式体验。

**Tech Stack:** Java 21, Spring Boot 3.3, Spring AI, JUnit 5 + Mockito, Vue 3 + TypeScript, Vitest

---

## File Structure

### Backend (new)
- `backend/src/main/java/com/mingming/agent/orchestrator/loop/AgentRunLoopService.java`
  - loop 服务接口，定义 execute 入参和返回报告。
- `backend/src/main/java/com/mingming/agent/orchestrator/loop/DefaultAgentRunLoopService.java`
  - loop 主实现，负责 turn 循环、终止策略和事件记录。
- `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopTerminationPolicy.java`
  - 轮次/时长/连续失败阈值与判断逻辑。
- `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopTerminationReason.java`
  - 终止原因枚举。
- `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopState.java`
  - 运行态（turnIndex、elapsedMs、consecutiveToolFailures 等）。
- `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopStepResult.java`
  - 单轮执行结果（工具次数、失败次数、finalAnswerDetected 等）。
- `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopExecutionReport.java`
  - 整体执行报告（终止原因、总轮次、总耗时、最终文本）。

### Backend (modified)
- `backend/src/main/java/com/mingming/agent/controller/ChatController.java`
  - 从 `orchestrator.runOnce(...)` 改为 `agentRunLoopService.execute(...)`。
- `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
  - 保留事件追加和上下文能力方法；移除新增的 loop 控制入口。
- `backend/src/main/java/com/mingming/agent/event/RunEventType.java`
  - 新增 `LOOP_TURN_STARTED`, `LOOP_TURN_FINISHED`, `LOOP_TERMINATED`。
- `backend/src/main/java/com/mingming/agent/event/contract/EventContractRegistry.java`
  - 注册并校验新增 loop 事件契约。
- `backend/src/main/java/com/mingming/agent/event/contract/LoopTurnStartedEventContract.java`
  - 新增契约类。
- `backend/src/main/java/com/mingming/agent/event/contract/LoopTurnFinishedEventContract.java`
  - 新增契约类。
- `backend/src/main/java/com/mingming/agent/event/contract/LoopTerminatedEventContract.java`
  - 新增契约类。

### Backend tests
- `backend/src/test/java/com/mingming/agent/orchestrator/loop/DefaultAgentRunLoopServiceTest.java`
  - loop 策略与事件顺序测试。
- `backend/src/test/java/com/mingming/agent/controller/ChatControllerTest.java`
  - controller 调用 loop service 的回归测试（若已有则修改）。
- `backend/src/test/java/com/mingming/agent/event/contract/LoopEventContractsTest.java`
  - loop payload 规范化与校验测试。
- `backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java`
  - 从 runOnce 主流程测试改为能力方法测试。

### Frontend (modified)
- `frontend/src/types/run.ts`
  - 增加 loop 状态类型定义。
- `frontend/src/services/eventMapper.ts`
  - 增加 loop 事件摘要映射。
- `frontend/src/services/eventMapper.test.ts`
  - 增加 loop 事件映射测试。
- `frontend/src/composables/useChatConsole.ts`
  - 增加 loop 状态聚合（当前轮次、终止原因、耗时）。
- `frontend/src/components/RunStatusPanel.vue`
  - 增加 loop 状态展示区域。
- `frontend/src/App.vue`
  - 透传 loop 状态属性到 `RunStatusPanel`。

### Frontend tests (new)
- `frontend/src/composables/useChatConsole.loop.test.ts`
  - 测试 loop 状态聚合与终止原因呈现。

---

### Task 1: 建立 Loop 领域模型与终止策略

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopTerminationReason.java`
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopTerminationPolicy.java`
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopState.java`
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopStepResult.java`
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopExecutionReport.java`
- Test: `backend/src/test/java/com/mingming/agent/orchestrator/loop/DefaultAgentRunLoopServiceTest.java`

- [ ] **Step 1: 先写失败测试（策略判断）**

```java
@Test
void shouldTerminateWhenMaxRoundsReached() {
    LoopTerminationPolicy policy = new LoopTerminationPolicy(8, 45_000, 2);
    LoopState state = new LoopState(8, Instant.now().minusMillis(1000), 1000, 0, "", false);
    LoopStepResult step = LoopStepResult.noFinal(0, 0, 120, "");

    Optional<LoopTerminationReason> reason = policy.check(state, step);

    assertThat(reason).contains(LoopTerminationReason.MAX_ROUNDS);
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=DefaultAgentRunLoopServiceTest#shouldTerminateWhenMaxRoundsReached test
```

Expected: FAIL，提示 `LoopTerminationPolicy` 或相关类型不存在。

- [ ] **Step 3: 实现最小策略与模型代码**

```java
public enum LoopTerminationReason {
    FINAL_ANSWER,
    MAX_ROUNDS,
    TIMEOUT,
    CONSECUTIVE_TOOL_FAILURES,
    ERROR
}

public record LoopTerminationPolicy(int maxRounds, long maxDurationMs, int maxConsecutiveToolFailures) {
    public Optional<LoopTerminationReason> check(LoopState state, LoopStepResult step) {
        if (step.finalAnswerDetected()) return Optional.of(LoopTerminationReason.FINAL_ANSWER);
        if (state.turnIndex() >= maxRounds) return Optional.of(LoopTerminationReason.MAX_ROUNDS);
        if (state.elapsedMs() >= maxDurationMs) return Optional.of(LoopTerminationReason.TIMEOUT);
        if (state.consecutiveToolFailures() >= maxConsecutiveToolFailures) {
            return Optional.of(LoopTerminationReason.CONSECUTIVE_TOOL_FAILURES);
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=DefaultAgentRunLoopServiceTest#shouldTerminateWhenMaxRoundsReached test
```

Expected: PASS。

- [ ] **Step 5: 提交本任务**

```bash
git add backend/src/main/java/com/mingming/agent/orchestrator/loop backend/src/test/java/com/mingming/agent/orchestrator/loop/DefaultAgentRunLoopServiceTest.java
git commit -m "feat: add single-run loop policy models"
```

### Task 2: 以 TDD 实现 AgentRunLoopService 主循环

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/loop/AgentRunLoopService.java`
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/loop/DefaultAgentRunLoopService.java`
- Modify: `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
- Test: `backend/src/test/java/com/mingming/agent/orchestrator/loop/DefaultAgentRunLoopServiceTest.java`

- [ ] **Step 1: 先写失败测试（8轮上限终止 + 终止事件）**

```java
@Test
void execute_shouldStopWithMaxRoundsAndPersistTerminationEvent() {
    UUID runId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(orchestratorFacade.runModelStep(any(), any(), any(), any())).thenReturn(LoopStepResult.noFinal(0, 0, 10, ""));

    LoopExecutionReport report = service.execute(runId, sessionId, "test", payload -> {});

    assertThat(report.terminationReason()).isEqualTo(LoopTerminationReason.MAX_ROUNDS);
    verify(orchestratorFacade, times(8)).appendEvent(eq(runId), anyInt(), eq(RunEventType.LOOP_TURN_STARTED), any());
    verify(orchestratorFacade, times(1)).appendEvent(eq(runId), anyInt(), eq(RunEventType.LOOP_TERMINATED), any());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=DefaultAgentRunLoopServiceTest#execute_shouldStopWithMaxRoundsAndPersistTerminationEvent test
```

Expected: FAIL，提示 service 未实现或断言不满足。

- [ ] **Step 3: 实现最小循环执行器**

```java
@Service
public class DefaultAgentRunLoopService implements AgentRunLoopService {
    @Override
    public LoopExecutionReport execute(UUID runId, UUID sessionId, String userText, Consumer<String> sseConsumer) {
        AtomicInteger seq = new AtomicInteger(1);
        LoopState state = LoopState.initial();

        while (!state.terminated()) {
            appendTurnStarted(runId, seq, state);
            LoopStepResult step = orchestratorFacade.runModelStep(runId, sessionId, userText, sseConsumer);
            state = state.next(step);
            appendTurnFinished(runId, seq, state, step);

            Optional<LoopTerminationReason> reason = policy.check(state, step);
            if (reason.isPresent()) {
                appendTerminated(runId, seq, state, reason.get());
                return state.toReport(reason.get());
            }
        }
        return state.toReport(LoopTerminationReason.ERROR);
    }
}
```

- [ ] **Step 4: 跑目标测试确认通过，再跑类级测试**

Run:

```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=DefaultAgentRunLoopServiceTest test
```

Expected: PASS，覆盖上限、超时、连续失败、final answer 四条路径。

- [ ] **Step 5: 提交本任务**

```bash
git add backend/src/main/java/com/mingming/agent/orchestrator/loop backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java backend/src/test/java/com/mingming/agent/orchestrator/loop/DefaultAgentRunLoopServiceTest.java
git commit -m "feat: implement single-run loop service with termination policy"
```

### Task 3: 接入 Controller 并补齐后端回归测试

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/controller/ChatController.java`
- Modify: `backend/src/test/java/com/mingming/agent/controller/ChatControllerTest.java` (若不存在则创建)
- Modify: `backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java`

- [ ] **Step 1: 先写失败测试（Controller 调 loop service）**

```java
@Test
void chatStream_shouldDelegateToRunLoopService() {
    ChatRequest req = new ChatRequest("你好", null);
    controller.chatStream(req);
    verify(agentRunLoopService).execute(any(), any(), eq("你好"), any());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run:

```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=ChatControllerTest#chatStream_shouldDelegateToRunLoopService test
```

Expected: FAIL，当前仍调用 `orchestrator.runOnce`。

- [ ] **Step 3: 修改 ChatController 依赖与调用路径**

```java
private final AgentRunLoopService agentRunLoopService;

agentRunLoopService.execute(runId, init.sessionId(), req.message(), data -> {
    emitter.send(SseEmitter.event().name("event").data(data));
});
```

- [ ] **Step 4: 跑 Controller + Orchestrator 相关测试**

Run:

```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=ChatControllerTest,AgentOrchestratorTest test
```

Expected: PASS；`AgentOrchestratorTest` 聚焦能力方法，而非完整 runOnce 控制流。

- [ ] **Step 5: 提交本任务**

```bash
git add backend/src/main/java/com/mingming/agent/controller/ChatController.java backend/src/test/java/com/mingming/agent/controller/ChatControllerTest.java backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java
git commit -m "refactor: route chat execution through run loop service"
```

### Task 4: 新增 Loop 事件类型与契约校验

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/event/RunEventType.java`
- Create: `backend/src/main/java/com/mingming/agent/event/contract/LoopTurnStartedEventContract.java`
- Create: `backend/src/main/java/com/mingming/agent/event/contract/LoopTurnFinishedEventContract.java`
- Create: `backend/src/main/java/com/mingming/agent/event/contract/LoopTerminatedEventContract.java`
- Test: `backend/src/test/java/com/mingming/agent/event/contract/LoopEventContractsTest.java`

- [ ] **Step 1: 写失败测试（缺字段时 contractWarnings 出现）**

```java
@Test
void terminatedContract_shouldWarnWhenReasonMissing() {
    ObjectNode payload = mapper.createObjectNode().put("turnIndex", 2);
    ObjectNode normalized = contract.normalize(payload);
    List<String> errors = contract.validate(normalized);
    assertThat(errors).contains("reason is required");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=LoopEventContractsTest test
```

Expected: FAIL（契约类不存在）。

- [ ] **Step 3: 实现事件枚举和契约**

```java
public enum RunEventType {
    USER_MESSAGE,
    MODEL_DELTA,
    MODEL_MESSAGE,
    RETRIEVAL_RESULT,
    RAG_SYNC,
    MCP_TOOLS_BOUND,
    MCP_CONFIRM_RESULT,
    LOOP_TURN_STARTED,
    LOOP_TURN_FINISHED,
    LOOP_TERMINATED,
    TOOL_CALL,
    TOOL_RESULT,
    ERROR
}
```

- [ ] **Step 4: 跑契约测试和全后端测试**

Run:

```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn test
```

Expected: PASS；新增 loop 事件在 registry 中被识别并校验。

- [ ] **Step 5: 提交本任务**

```bash
git add backend/src/main/java/com/mingming/agent/event backend/src/test/java/com/mingming/agent/event/contract/LoopEventContractsTest.java
git commit -m "feat: add loop run-event contracts and validation"
```

### Task 5: 前端 eventMapper 适配 Loop 事件（TDD）

**Files:**
- Modify: `frontend/src/services/eventMapper.ts`
- Modify: `frontend/src/services/eventMapper.test.ts`

- [ ] **Step 1: 增加失败测试（loop 三类事件摘要）**

```ts
it('builds loop terminated summary with reason and turn', () => {
  const item = mapRunEventToTimelineItem({
    id: 'loop-term-1',
    runId: 'run-1',
    seq: 9,
    createdAt: '2026-04-07T10:00:00Z',
    type: 'LOOP_TERMINATED',
    payload: JSON.stringify({ turnIndex: 4, reason: 'TIMEOUT', elapsedMs: 45012 }),
  })

  expect(item.summary).toContain('Loop 终止')
  expect(item.summary).toContain('第 4 轮')
  expect(item.summary).toContain('TIMEOUT')
})
```

- [ ] **Step 2: 运行前端测试确认失败**

Run:

```bash
cd frontend && npm run test:unit -- src/services/eventMapper.test.ts
```

Expected: FAIL，未知事件类型无法生成目标摘要。

- [ ] **Step 3: 实现 loop 摘要映射函数**

```ts
function summarizeLoopPayload(payload: unknown, eventType: string): string | null {
  if (!payload || typeof payload !== 'object') return null
  const p = payload as Record<string, unknown>
  const turn = typeof p.turnIndex === 'number' ? p.turnIndex : 0
  const elapsed = typeof p.elapsedMs === 'number' ? p.elapsedMs : 0
  if (eventType === 'LOOP_TURN_STARTED') return `Loop 第 ${turn} 轮开始 | 已耗时: ${elapsed}ms`
  if (eventType === 'LOOP_TURN_FINISHED') return `Loop 第 ${turn} 轮结束 | 已耗时: ${elapsed}ms`
  if (eventType === 'LOOP_TERMINATED') {
    const reason = typeof p.reason === 'string' ? p.reason : 'UNKNOWN'
    return `Loop 终止 | 第 ${turn} 轮 | 原因: ${reason} | 耗时: ${elapsed}ms`
  }
  return null
}
```

- [ ] **Step 4: 运行 eventMapper 测试确认通过**

Run:

```bash
cd frontend && npm run test:unit -- src/services/eventMapper.test.ts
```

Expected: PASS。

- [ ] **Step 5: 提交本任务**

```bash
git add frontend/src/services/eventMapper.ts frontend/src/services/eventMapper.test.ts
git commit -m "feat: map loop events into readable timeline summaries"
```

### Task 6: 前端状态聚合与 RunStatusPanel 展示

**Files:**
- Modify: `frontend/src/types/run.ts`
- Modify: `frontend/src/composables/useChatConsole.ts`
- Modify: `frontend/src/components/RunStatusPanel.vue`
- Modify: `frontend/src/App.vue`
- Create: `frontend/src/composables/useChatConsole.loop.test.ts`

- [ ] **Step 1: 先写失败测试（loop 状态聚合）**

```ts
it('derives latest loop state from timeline events', () => {
  const state = deriveLoopState([
    { type: 'LOOP_TURN_STARTED', rawPayload: '{"turnIndex":1,"elapsedMs":10}' },
    { type: 'LOOP_TERMINATED', rawPayload: '{"turnIndex":3,"reason":"MAX_ROUNDS","elapsedMs":40123}' },
  ])

  expect(state.currentTurn).toBe(3)
  expect(state.terminationReason).toBe('MAX_ROUNDS')
})
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
cd frontend && npm run test:unit -- src/composables/useChatConsole.loop.test.ts
```

Expected: FAIL，`deriveLoopState` 或 loop 类型未定义。

- [ ] **Step 3: 实现类型、聚合函数和面板渲染**

```ts
export interface LoopStatus {
  currentTurn: number
  maxTurns: number
  elapsedMs: number
  terminationReason: string
  active: boolean
}
```

```vue
<section v-if="loopStatus" class="status-section">
  <div class="status-section-head">
    <h3>Loop 状态</h3>
    <span class="status-window-pill">实时</span>
  </div>
  <dl class="status-grid">
    <div><dt>轮次</dt><dd>{{ loopStatus.currentTurn }} / {{ loopStatus.maxTurns }}</dd></div>
    <div><dt>耗时</dt><dd>{{ loopStatus.elapsedMs }} ms</dd></div>
    <div class="status-cell--wide"><dt>终止原因</dt><dd>{{ loopStatus.terminationReason || '未终止' }}</dd></div>
  </dl>
</section>
```

- [ ] **Step 4: 运行前端测试与构建**

Run:

```bash
cd frontend && npm run test:unit && npm run build
```

Expected: PASS；构建通过，面板可见且移动端未破版。

- [ ] **Step 5: 提交本任务**

```bash
git add frontend/src/types/run.ts frontend/src/composables/useChatConsole.ts frontend/src/components/RunStatusPanel.vue frontend/src/App.vue frontend/src/composables/useChatConsole.loop.test.ts
git commit -m "feat: surface loop status in console inspector"
```

### Task 7: 端到端回归与文档更新

**Files:**
- Modify: `docs/changes-log.md`
- Modify: `docs/project-overview.md`

- [ ] **Step 1: 更新变更日志（新增 loop 架构/事件/前端适配）**

```md
## 2026-04-07 迭代（单 Run Agent Loop）
- 新增 AgentRunLoopService，支持 8 轮/45s/连续失败2次终止。
- run_event 新增 LOOP_TURN_STARTED/FINISHED/TERMINATED。
- 前端状态面板新增 loop 轮次与终止原因显示。
```

- [ ] **Step 2: 更新项目概览（下一阶段计划同步）**

```md
- 已实现：单 run 显式 loop 可观测执行。
- 后续：在此基础上演进跨 run 工作流。
```

- [ ] **Step 3: 跑完整验证命令**

Run:

```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn test
cd frontend && npm run test:unit && npm run build
```

Expected: 全部 PASS。

- [ ] **Step 4: 手工验证关键场景**

Run:

```bash
# 启动后端后，在前端发起 4 类对话：
# 1) 无工具普通问答
# 2) 会触发工具成功的问答
# 3) 触发工具失败的问答
# 4) 长问题触发超时
```

Expected:
- 时间线出现 loop 三类事件；
- 状态面板显示轮次和终止原因；
- 聊天主流程流式文本无回归。

- [ ] **Step 5: 提交本任务**

```bash
git add docs/project-overview.md docs/changes-log.md
git commit -m "docs: record single-run loop architecture and observability"
```

---

## Self-Review Checklist

- 规格覆盖：
  - 单 run 显式循环 -> Task 2, 3
  - 终止策略（8轮/45秒/连续失败2次）-> Task 1, 2
  - 事件契约与回放兼容 -> Task 4, 5
  - 前端可解释展示 -> Task 5, 6
  - 验收与回归 -> Task 7
- 占位符检查：无 `TODO/TBD/implement later`。
- 类型一致性：`LoopTerminationReason`, `LoopExecutionReport`, `LoopStatus` 在任务间命名一致。
