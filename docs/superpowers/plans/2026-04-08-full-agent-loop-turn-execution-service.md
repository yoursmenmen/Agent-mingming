# Full Agent Loop (TurnExecutionService) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前 single-turn 包装升级为真实多轮单-run agent loop（8轮/45秒/连续工具失败2次），并由 `LoopStepResult` 驱动继续/终止。

**Architecture:** 新增 `TurnExecutionService` 承担单轮执行，`AgentRunLoopService` 专注循环与策略终止，`AgentOrchestrator` 只做运行入口、事件桥接与依赖装配。主入口从 `executeSingleTurn(maxRounds=1)` 升级为完整 loop 策略，事件仍通过 `appendEvent` 进入 run_event。

**Tech Stack:** Java 21, Spring Boot 3.3, Spring AI, JUnit5, Mockito, Vue3, TypeScript, Vitest

---

## File Structure

### Backend (create)
- `backend/src/main/java/com/mingming/agent/orchestrator/turn/TurnExecutionService.java`
  - 单轮执行接口，返回 `LoopStepResult`。
- `backend/src/main/java/com/mingming/agent/orchestrator/turn/DefaultTurnExecutionService.java`
  - 单轮执行实现：历史、检索、模型/工具执行、结果归并。
- `backend/src/main/java/com/mingming/agent/orchestrator/turn/TurnContext.java`
  - 单轮上下文载体（run/session/turn/seq/sse）。

### Backend (modify)
- `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
  - 主入口改为完整 loop 策略，调用 `TurnExecutionService`。
- `backend/src/main/java/com/mingming/agent/controller/ChatController.java`
  - 确认调用完整 loop 入口。
- `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopStepResult.java`
  - 扩展字段（toolCallCount/assistantContent/meta）。

### Backend tests
- `backend/src/test/java/com/mingming/agent/orchestrator/turn/DefaultTurnExecutionServiceTest.java`
  - 单轮执行返回值语义测试。
- `backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java`
  - 入口策略与 LOOP 事件落库链路回归。
- `backend/src/test/java/com/mingming/agent/controller/ChatControllerTest.java`
  - controller 委派行为不回退。
- `backend/src/test/java/com/mingming/agent/orchestrator/loop/DefaultAgentRunLoopServiceTest.java`
  - 保持策略测试稳定通过。

### Frontend (modify)
- `frontend/src/services/eventMapper.ts`
  - 确认 loop 字段解析固定为 `turnIndex/elapsedMs/reason`。
- `frontend/src/composables/useChatConsole.ts`
  - 保持 loop 聚合字段契约与后端一致。
- `frontend/src/services/eventMapper.test.ts`
  - 增补字段契约断言。
- `frontend/src/composables/useChatConsole.loop.test.ts`
  - 增补多轮聚合断言。

---

### Task 1: 建立 TurnExecutionService 与 TurnContext（TDD）

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/turn/TurnExecutionService.java`
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/turn/TurnContext.java`
- Create: `backend/src/test/java/com/mingming/agent/orchestrator/turn/DefaultTurnExecutionServiceTest.java`

- [ ] **Step 1: 写失败测试，定义接口契约**

```java
@Test
void executeTurn_shouldReturnNonNullLoopStepResult() {
    TurnExecutionService service = context -> new LoopStepResult(false, false, 0, null, Map.of());
    LoopStepResult result = service.executeTurn(new TurnContext(
            UUID.randomUUID(), UUID.randomUUID(), "hi", 1, new AtomicInteger(1), s -> {}));
    assertThat(result).isNotNull();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=DefaultTurnExecutionServiceTest test
```

Expected: FAIL（`TurnExecutionService`/`TurnContext` 不存在）。

- [ ] **Step 3: 实现最小接口与上下文类型**

```java
public interface TurnExecutionService {
    LoopStepResult executeTurn(TurnContext context);
}

public record TurnContext(
        UUID runId,
        UUID sessionId,
        String userText,
        int turnIndex,
        AtomicInteger seq,
        Consumer<String> sseDataConsumer) {}
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=DefaultTurnExecutionServiceTest test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/mingming/agent/orchestrator/turn backend/src/test/java/com/mingming/agent/orchestrator/turn/DefaultTurnExecutionServiceTest.java
git commit -m "feat: introduce turn execution service contract"
```

### Task 2: 扩展 LoopStepResult 为真实继续判定载体（TDD）

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopStepResult.java`
- Modify: `backend/src/test/java/com/mingming/agent/orchestrator/loop/DefaultAgentRunLoopServiceTest.java`

- [ ] **Step 1: 写失败测试，断言扩展字段可用**

```java
@Test
void loopStepResult_shouldExposeExtendedFields() {
    LoopStepResult result = new LoopStepResult(true, false, 2, "done", Map.of("k", "v"));
    assertThat(result.toolCallCount()).isEqualTo(2);
    assertThat(result.assistantContent()).isEqualTo("done");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=DefaultAgentRunLoopServiceTest test
```

Expected: FAIL（构造器/字段不匹配）。

- [ ] **Step 3: 扩展模型并兼容既有调用**

```java
public record LoopStepResult(
        boolean finalAnswerReady,
        boolean toolFailure,
        int toolCallCount,
        String assistantContent,
        Map<String, Object> meta) {

    public LoopStepResult(boolean finalAnswerReady, boolean toolFailure) {
        this(finalAnswerReady, toolFailure, 0, null, Map.of());
    }
}
```

- [ ] **Step 4: 运行 loop 测试确认通过**

Run:
```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=DefaultAgentRunLoopServiceTest test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/mingming/agent/orchestrator/loop/LoopStepResult.java backend/src/test/java/com/mingming/agent/orchestrator/loop/DefaultAgentRunLoopServiceTest.java
git commit -m "feat: extend loop step result with turn-level metadata"
```

### Task 3: 实现 DefaultTurnExecutionService 单轮执行（TDD）

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/turn/DefaultTurnExecutionService.java`
- Modify: `backend/src/test/java/com/mingming/agent/orchestrator/turn/DefaultTurnExecutionServiceTest.java`

- [ ] **Step 1: 写失败测试，校验 finalAnswerReady 语义**

```java
@Test
void executeTurn_shouldMarkFinalAnswerReadyWhenModelReturnsContent() {
    DefaultTurnExecutionService service = buildServiceReturning("ok", false, 0);
    LoopStepResult result = service.executeTurn(sampleContext());
    assertThat(result.finalAnswerReady()).isTrue();
    assertThat(result.toolFailure()).isFalse();
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=DefaultTurnExecutionServiceTest test
```

Expected: FAIL（实现缺失）。

- [ ] **Step 3: 实现最小单轮执行器**

```java
@Service
public class DefaultTurnExecutionService implements TurnExecutionService {
    @Override
    public LoopStepResult executeTurn(TurnContext context) {
        String content = delegateRunOnceAndGetContent(context);
        boolean finalReady = content != null && !content.isBlank();
        return new LoopStepResult(finalReady, false, 0, content, Map.of());
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=DefaultTurnExecutionServiceTest test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/mingming/agent/orchestrator/turn/DefaultTurnExecutionService.java backend/src/test/java/com/mingming/agent/orchestrator/turn/DefaultTurnExecutionServiceTest.java
git commit -m "feat: implement default turn execution service"
```

### Task 4: 主入口升级为完整 loop 策略（8/45000/2）（TDD）

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
- Modify: `backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java`
- Modify: `backend/src/main/java/com/mingming/agent/controller/ChatController.java`
- Modify: `backend/src/test/java/com/mingming/agent/controller/ChatControllerTest.java`

- [ ] **Step 1: 写失败测试，断言入口策略参数为 8/45000/2**

```java
@Test
void executeSingleTurn_shouldUseFullLoopPolicy() {
    orchestrator.executeSingleTurn(runId, sessionId, "hi", s -> {});
    verify(loopService).execute(argThat(p ->
            Integer.valueOf(8).equals(p.maxRounds()) &&
            Long.valueOf(45_000L).equals(p.maxDurationMs()) &&
            Integer.valueOf(2).equals(p.maxConsecutiveToolFailures())), any(), any());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=AgentOrchestratorTest,ChatControllerTest test
```

Expected: FAIL（当前仍是 `maxRounds=1`）。

- [ ] **Step 3: 实现入口升级并接入 TurnExecutionService**

```java
LoopTerminationPolicy policy = new LoopTerminationPolicy(8, 45_000L, 2);
executeLoop(runId, seq, policy, turnIndex ->
        turnExecutionService.executeTurn(new TurnContext(runId, sessionId, userText, turnIndex, seq, sseDataConsumer)));
```

- [ ] **Step 4: 运行后端关键测试确认通过**

Run:
```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn -Dtest=AgentOrchestratorTest,ChatControllerTest,DefaultAgentRunLoopServiceTest test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java backend/src/main/java/com/mingming/agent/controller/ChatController.java backend/src/test/java/com/mingming/agent/controller/ChatControllerTest.java
git commit -m "feat: upgrade chat entry to full single-run agent loop"
```

### Task 5: 确认 loop 事件与前端契约一致（TDD）

**Files:**
- Modify: `frontend/src/services/eventMapper.ts`
- Modify: `frontend/src/composables/useChatConsole.ts`
- Modify: `frontend/src/services/eventMapper.test.ts`
- Modify: `frontend/src/composables/useChatConsole.loop.test.ts`

- [ ] **Step 1: 写失败测试，锁定字段口径**

```ts
it('maps loop payload with turnIndex and elapsedMs', () => {
  const item = mapRunEventToTimelineItem({
    type: 'LOOP_TURN_FINISHED',
    payload: JSON.stringify({ turnIndex: 3, elapsedMs: 1200 }),
  } as any)
  expect(item.summary).toContain('第 3 轮')
  expect(item.summary).toContain('1200')
})
```

- [ ] **Step 2: 运行前端测试确认失败**

Run:
```bash
cd frontend && npm run test:unit -- src/services/eventMapper.test.ts src/composables/useChatConsole.loop.test.ts
```

Expected: FAIL（若字段/文案不一致）。

- [ ] **Step 3: 调整映射与聚合逻辑**

```ts
const turnIndex = toFiniteNumber(payload.turnIndex)
const elapsedMs = toFiniteNumber(payload.elapsedMs)
const reason = typeof payload.reason === 'string' ? payload.reason : ''
```

- [ ] **Step 4: 运行前端测试确认通过**

Run:
```bash
cd frontend && npm run test:unit -- src/services/eventMapper.test.ts src/composables/useChatConsole.loop.test.ts
```

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/services/eventMapper.ts frontend/src/composables/useChatConsole.ts frontend/src/services/eventMapper.test.ts frontend/src/composables/useChatConsole.loop.test.ts
git commit -m "fix: align loop frontend contract with backend payload fields"
```

### Task 6: 全量验证与文档同步

**Files:**
- Modify: `docs/changes-log.md`
- Modify: `docs/project-overview.md`
- Modify: `docs/Agent-MM.md`

- [ ] **Step 1: 更新变更日志**

```md
## 2026-04-08 迭代（Full Agent Loop）
- 单 run loop 入口升级为 8/45s/连续失败2次。
- 引入 TurnExecutionService，继续判定由 LoopStepResult 提供。
- 前后端 loop payload 契约统一为 turnIndex/elapsedMs/reason。
```

- [ ] **Step 2: 更新项目概览与亮点文档**

```md
- 当前主链路已为完整单 run 多轮 loop，不再是 maxRounds=1 壳实现。
```

- [ ] **Step 3: 执行完整验证命令**

Run:
```bash
cd backend && export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && mvn test
cd frontend && npm run test:unit && npm run build
```

Expected: 全部 PASS。

- [ ] **Step 4: 手工回归 4 场景**

```bash
# 1) 普通问答
# 2) 工具成功
# 3) 工具失败连续2次
# 4) 长问题触发超时
```

Expected:
- LOOP 事件可回放
- 状态面板显示轮次/耗时/原因
- SSE 主体验不回归

- [ ] **Step 5: 提交**

```bash
git add docs/changes-log.md docs/project-overview.md docs/Agent-MM.md
git commit -m "docs: record full agent loop rollout and contract semantics"
```

---

## Self-Review Checklist

- Spec coverage:
  - 方案 B（TurnExecutionService）-> Task 1,3,4
  - 完整策略 8/45s/2 -> Task 4
  - LoopStepResult 一手判定 -> Task 2,3,4
  - 前后端契约一致 -> Task 5
  - 验证与文档闭环 -> Task 6
- Placeholder scan: 无 `TODO/TBD/implement later`。
- Type consistency: `TurnContext`, `TurnExecutionService`, `LoopStepResult` 全文一致。
