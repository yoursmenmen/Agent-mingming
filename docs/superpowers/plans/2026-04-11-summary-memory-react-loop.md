# ReAct 上下文窗口 + Summary Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 ReAct Agent Loop 增加可持久化的摘要记忆，并在每轮推理前构造 `system + summary + recent window + 当前问题`，稳定控制上下文规模。

**Architecture:** 首先引入运行时滑动窗口（控制消息数/字符预算），确保单次 run 不无限膨胀；然后引入会话级 Summary Memory（持久化到 run_event），把窗口外历史压缩成一条摘要消息；最后统一 prompt 组装顺序并补齐测试与文档。

**Tech Stack:** Java 21, Spring Boot 3.3, Spring AI, JPA Repository, JUnit 5, Mockito

---

## 文件职责与改动边界

- Create: `backend/src/main/java/com/mingming/agent/react/ContextWindowPolicy.java`
  - ReAct 运行时窗口策略（最大消息数、最大字符数、前缀保留条数）
- Create: `backend/src/main/java/com/mingming/agent/react/ContextWindowManager.java`
  - 对消息列表执行滑动窗口裁剪（保留 system/summary 前缀）
- Create: `backend/src/main/java/com/mingming/agent/react/memory/SessionSummaryService.java`
  - 读取历史摘要、生成新摘要、持久化摘要事件
- Modify: `backend/src/main/java/com/mingming/agent/react/ReactAgentService.java`
  - 引入窗口管理 + summary 注入 + 结束后摘要刷新
- Modify: `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
  - 补充“仅历史消息”组装接口（不自动拼当前 user）
- Modify: `backend/src/main/java/com/mingming/agent/repository/RunEventRepository.java`
  - 增加按 session 查询最新 `SESSION_SUMMARY` 的接口
- Modify: `backend/src/main/java/com/mingming/agent/event/RunEventType.java`
  - 新增 `SESSION_SUMMARY`
- Test: `backend/src/test/java/com/mingming/agent/react/ContextWindowManagerTest.java`
- Test: `backend/src/test/java/com/mingming/agent/react/memory/SessionSummaryServiceTest.java`
- Test: `backend/src/test/java/com/mingming/agent/react/ReactAgentServiceTest.java`（或在现有 orchestrator/react 测试中补用例）
- Modify Docs: `docs/project-overview.md`, `docs/progress-status.md`, `docs/changes-log.md`

---

### Task 1: 运行时滑动窗口（先落地可控上下文）

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/react/ContextWindowPolicy.java`
- Create: `backend/src/main/java/com/mingming/agent/react/ContextWindowManager.java`
- Modify: `backend/src/main/java/com/mingming/agent/react/ReactAgentService.java`
- Test: `backend/src/test/java/com/mingming/agent/react/ContextWindowManagerTest.java`

- [ ] **Step 1: 先写失败测试（窗口裁剪行为）**

```java
@Test
void trim_shouldPreservePrefixAndKeepLatestTailWithinBudget() {
    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage("system"));
    messages.add(new SystemMessage("summary"));
    messages.add(new UserMessage("u1"));
    messages.add(new AssistantMessage("a1"));
    messages.add(new UserMessage("u2"));
    messages.add(new AssistantMessage("a2"));

    ContextWindowPolicy policy = new ContextWindowPolicy(4, 20);
    ContextWindowManager manager = new ContextWindowManager(policy);

    manager.trimInPlace(messages, 2);

    assertThat(messages.get(0).getText()).isEqualTo("system");
    assertThat(messages.get(1).getText()).isEqualTo("summary");
    assertThat(messages).hasSizeLessThanOrEqualTo(4);
    assertThat(messages.get(messages.size() - 1).getText()).isEqualTo("a2");
}
```

- [ ] **Step 2: 运行测试并确认失败（RED）**

Run: `mvn -Dtest=ContextWindowManagerTest test`
Expected: FAIL（`ContextWindowManager` 不存在或行为未实现）

- [ ] **Step 3: 写最小实现让测试通过（GREEN）**

```java
public final class ContextWindowManager {
    private final ContextWindowPolicy policy;

    public ContextWindowManager(ContextWindowPolicy policy) {
        this.policy = policy;
    }

    public void trimInPlace(List<Message> messages, int fixedPrefixCount) {
        // 保留前缀后，从尾部反向挑选直到满足消息数/字符预算
    }
}
```

- [ ] **Step 4: 接入 ReactAgentService（每轮工具结果后裁剪）**

```java
messages.add(AssistantMessage.builder().content(assistantText).toolCalls(toolCalls).build());
messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
contextWindowManager.trimInPlace(messages, fixedPrefixCount);
```

- [ ] **Step 5: 运行测试确认通过并提交**

Run: `mvn -Dtest=ContextWindowManagerTest test`
Expected: PASS

Commit:

```bash
git add backend/src/main/java/com/mingming/agent/react/ContextWindowPolicy.java \
        backend/src/main/java/com/mingming/agent/react/ContextWindowManager.java \
        backend/src/main/java/com/mingming/agent/react/ReactAgentService.java \
        backend/src/test/java/com/mingming/agent/react/ContextWindowManagerTest.java
git commit -m "feat(react): add runtime sliding context window"
```

---

### Task 2: 可持久化 Summary Memory（会话级）

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/event/RunEventType.java`
- Modify: `backend/src/main/java/com/mingming/agent/repository/RunEventRepository.java`
- Create: `backend/src/main/java/com/mingming/agent/react/memory/SessionSummaryService.java`
- Test: `backend/src/test/java/com/mingming/agent/react/memory/SessionSummaryServiceTest.java`

- [ ] **Step 1: 先写失败测试（读取最新 summary + 生成与持久化）**

```java
@Test
void loadLatestSummary_shouldReturnNewestContent() {
    UUID sessionId = UUID.randomUUID();
    // mock repository return latest SESSION_SUMMARY event payload {"content":"..."}
    // assert service.loadLatestSummary(sessionId).orElseThrow() == "..."
}

@Test
void refreshSummary_shouldPersistSessionSummaryEvent() {
    // given old summary + recent transcript
    // when refreshSummary(...)
    // then orchestrator.appendEvent(... RunEventType.SESSION_SUMMARY ...)
}
```

- [ ] **Step 2: 运行测试并确认失败（RED）**

Run: `mvn -Dtest=SessionSummaryServiceTest test`
Expected: FAIL（类型/方法未实现）

- [ ] **Step 3: 最小实现 SessionSummaryService（GREEN）**

```java
public Optional<String> loadLatestSummary(UUID sessionId) { ... }

public Optional<String> refreshSummary(
        UUID runId,
        UUID sessionId,
        String previousSummary,
        List<ConversationTurn> recentTurns,
        ChatModel chatModel,
        AtomicInteger seqCounter) {
    // 使用专用 summary prompt 压缩历史
    // appendEvent(runId, seq++, SESSION_SUMMARY, payload)
}
```

- [ ] **Step 4: 更新 repository 查询与事件类型**

```java
@Query(value = """
        SELECT e.*
        FROM run_event e
        JOIN agent_run r ON r.id = e.run_id
        WHERE r.session_id = :sessionId
          AND e.type = 'SESSION_SUMMARY'
        ORDER BY e.created_at DESC, e.seq DESC
        LIMIT 1
        """, nativeQuery = true)
Optional<RunEventEntity> findLatestSessionSummaryEvent(@Param("sessionId") UUID sessionId);
```

- [ ] **Step 5: 运行测试确认通过并提交**

Run: `mvn -Dtest=SessionSummaryServiceTest test`
Expected: PASS

Commit:

```bash
git add backend/src/main/java/com/mingming/agent/event/RunEventType.java \
        backend/src/main/java/com/mingming/agent/repository/RunEventRepository.java \
        backend/src/main/java/com/mingming/agent/react/memory/SessionSummaryService.java \
        backend/src/test/java/com/mingming/agent/react/memory/SessionSummaryServiceTest.java
git commit -m "feat(memory): persist and load session summary"
```

---

### Task 3: 合并策略接线（system + summary + recent window + 当前问题）

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
- Modify: `backend/src/main/java/com/mingming/agent/react/ReactAgentService.java`
- Test: `backend/src/test/java/com/mingming/agent/react/ReactAgentServiceTest.java`

- [ ] **Step 1: 先写失败测试（prompt 组装顺序）**

```java
@Test
void buildInitialMessages_shouldComposeSystemSummaryRecentWindowAndCurrentUser() {
    // given summary exists + history exists
    // when execute(...) first turn
    // then prompt messages order:
    // 1) BASE_SYSTEM_PROMPT
    // 2) summary system message
    // 3) trimmed recent history
    // 4) current UserMessage
}
```

- [ ] **Step 2: 运行测试并确认失败（RED）**

Run: `mvn -Dtest=ReactAgentServiceTest test`
Expected: FAIL（当前实现顺序不满足）

- [ ] **Step 3: 最小实现组装顺序（GREEN）**

```java
messages.add(new SystemMessage(BASE_SYSTEM_PROMPT));
summaryService.loadLatestSummary(sessionId)
        .ifPresent(s -> messages.add(new SystemMessage("会话摘要记忆:\n" + s)));
messages.addAll(orchestrator.buildSessionHistoryMessages(sessionId));
messages.add(new UserMessage(userText));
```

- [ ] **Step 4: 在 run 结束点刷新 summary**

```java
summaryService.refreshSummary(
    runId,
    sessionId,
    summaryText,
    transcriptTurns,
    chatModel,
    seq);
```

- [ ] **Step 5: 跑测试并提交**

Run: `mvn -Dtest=ReactAgentServiceTest,AgentOrchestratorTest test`
Expected: PASS

Commit:

```bash
git add backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java \
        backend/src/main/java/com/mingming/agent/react/ReactAgentService.java \
        backend/src/test/java/com/mingming/agent/react/ReactAgentServiceTest.java
git commit -m "feat(react): compose prompts with summary and recent window"
```

---

### Task 4: 回归验证与文档同步

**Files:**
- Modify: `docs/project-overview.md`
- Modify: `docs/progress-status.md`
- Modify: `docs/changes-log.md`

- [ ] **Step 1: 运行关键后端测试集**

Run:

```bash
export JAVA_HOME="/c/Env/Java/Java21"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -Dtest=ContextWindowManagerTest,SessionSummaryServiceTest,ReactAgentServiceTest,AgentOrchestratorTest test
```

Expected: PASS（0 failures）

- [ ] **Step 2: 更新文档（行为说明 + 配置说明）**

关键补充点：
- 新的 prompt 组装顺序
- `SESSION_SUMMARY` 事件说明
- 长会话性能与质量收益

- [ ] **Step 3: 提交文档与收尾提交**

```bash
git add docs/project-overview.md docs/progress-status.md docs/changes-log.md
git commit -m "docs: document summary memory and context window strategy"
```

---

## 可选实现项（需要你拍板）

1. **Summary 生成频率（推荐：每次 run 结束都刷新）**
   - 方案 A：每次 run 刷新（最简单，质量稳定）
   - 方案 B：每 N 次 run 刷新（更省成本）

2. **Summary 写入位置（推荐：`run_event` 新类型 `SESSION_SUMMARY`）**
   - 方案 A：继续放 `run_event`（无额外迁移，复用现有链路）
   - 方案 B：独立 `session_memory` 表（结构更清晰，但要新建迁移和维护成本）

3. **Summary 失败降级（推荐：保留旧 summary，不阻断主回答）**
   - 方案 A：静默降级（对用户无感）
   - 方案 B：写 `RUN_TERMINATED` / `ERROR` 提示（可观测更强，但会影响体验）

---

## 执行顺序与提交策略

按你的要求分步提交：

1. `feat(react): add runtime sliding context window`
2. `feat(memory): persist and load session summary`
3. `feat(react): compose prompts with summary and recent window`
4. `docs: document summary memory and context window strategy`

每一步都先 RED（失败测试）-> GREEN（最小实现）-> 验证通过 -> commit。
