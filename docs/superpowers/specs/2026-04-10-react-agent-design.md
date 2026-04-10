# ReAct Agent 设计文档

> 日期：2026-04-10
> 状态：待实施

---

## 1. 背景与目标

### 1.1 现状

- 当前主流程（`AgentOrchestrator.runOnce`）是单次 LLM 调用，工具结果由 Spring AI 内部处理，无法逐步落库。
- Codex 的 agent loop 重构引入了 `[FINAL]/[CONTINUE]` 标记协议，导致简单对话也触发多轮循环并输出错误提示，已决定回退。

### 1.2 目标

构建一个**透明可观测的 ReAct Agent**，能够：

1. 接受复杂任务（如 GitHub 仓库链接），自主分解并执行。
2. 每一步（思考、工具调用、工具结果）全部落库为 `run_event`，完整可回放。
3. 工具执行分级确认：读操作自动运行，写操作/删除操作需用户确认。
4. 无法自动完成时，明确告知用户需要手动执行哪些步骤。

### 1.3 非目标（本阶段不做）

- 多 Agent 协作 / 并行执行
- 跨 run 暂停与恢复
- LangGraph4j 图编排
- Qwen3-thinking 扩展思考 token 解析（后期扩展）

---

## 2. 架构设计

### 2.1 组件职责

```
ChatController
  ├─ startRun()                    → AgentOrchestrator（创建 session/run）
  └─ ReactAgentService.execute()   → ReAct 主循环
       ├─ 构建初始 messages         → AgentOrchestrator.buildPromptMessages()
       ├─ 每轮 LLM 流式调用         → chatModel.stream(messages)，token 实时 SSE 推送
       ├─ 落库 MODEL_OUTPUT 事件    → AgentOrchestrator.appendEvent()
       ├─ 提取 tool_calls
       │    └─ ToolDispatcher.dispatch()
       │         ├─ 白名单 → 自动执行
       │         ├─ 需确认 → 落库 TOOL_CONFIRM_REQUIRED
       │         │           SSE 推送确认卡片数据
       │         │           ToolConfirmRegistry.awaitConfirm() 挂起等待
       │         └─ 执行结果 → 落库 TOOL_CALL + TOOL_RESULT 事件
       ├─ 追加 [assistant + tool_results] 到 messages
       └─ 无 tool_calls → 结束，SSE 推送最终答案
```

### 2.2 层次划分

| 层 | 类 | 职责 |
|---|---|---|
| 入口层 | `ChatController` | HTTP/SSE 接入，不变 |
| 编排层 | `AgentOrchestrator` | 创建 run/session，提供 buildPromptMessages、appendEvent 等能力方法 |
| Loop 层 | `ReactAgentService` | ReAct 循环主体，管理轮次、终止、SSE 推送 |
| 工具层 | `ToolDispatcher` | 工具路由、分级确认、执行、结果序列化 |
| 工具实现 | `ShellTool` / `FileTool` / `FetchTool` | 各工具具体实现 |

### 2.3 执行流程（单 run）

```
用户发送消息
  → ChatController 创建 run，推送 RUN_STARTED SSE
  → ReactAgentService.execute(runId, sessionId, userText, sseConsumer)
      loop:
        1. chatModel.stream(messages)          # 单次 LLM 流式调用，token 实时 SSE 推送给用户
        2. 落库 MODEL_OUTPUT 事件
        3. 流式 token 实时 SSE 推送；同时累积完整响应用于提取 tool_calls
        4. 检查 response.toolCalls
           ├─ 空 → break（最终答案，loop 结束）
           └─ 非空 → 逐个处理：
               a. 落库 TOOL_CALL 事件（工具名 + 参数）
               b. ToolDispatcher.dispatch(toolCall)
                  ├─ 白名单 → 直接执行
                  └─ 需确认 → SSE 推送 TOOL_CONFIRM_REQUIRED，挂起等待
               c. 落库 TOOL_RESULT 事件（结果或错误）
               d. SSE 推送工具执行摘要
        5. messages.append(assistantMessage + toolResults)
      end loop
  → 落库 RUN_COMPLETED 事件，SSE 推送完成信号
```

---

## 3. 工具设计

### 3.1 工具清单

| 工具名 | 说明 | 确认级别 |
|---|---|---|
| `fetch_page` | HTTP GET 页面内容（已有） | 自动 |
| `shell_exec` | 执行 shell 命令 | 分级（见下） |
| `file_read` | 读取文件内容 | 自动 |
| `file_write` | 写入/修改文件 | 需确认 |
| `file_delete` | 删除文件或目录 | 需确认 |

### 3.2 shell_exec 分级规则

**白名单（自动执行，无需确认）**：

```
ls, ll, pwd, echo, cat, head, tail, which, type,
git clone, git status, git log, git diff,
node --version, npm --version, java --version,
python --version, mvn --version,
find（只读场景）
```

**需用户确认**：

- `npm install` / `pip install` / `mvn install`（写入依赖）
- `rm` / `rmdir`（删除）
- 写入系统配置（`/etc/`、`~/.bashrc` 等）
- 任何带 `sudo` 的命令
- 其他不在白名单内的命令（默认需确认）

**禁止执行**（直接拒绝，告知用户）：

- `rm -rf /`、`shutdown`、`format` 等破坏性命令
- 包含管道重定向到敏感路径的命令

### 3.3 工具接口定义

```java
public interface AgentTool {
    String name();
    String description();
    ToolResult execute(Map<String, Object> args);
}

public record ToolResult(
    boolean success,
    String output,       // 成功时的输出内容
    String error,        // 失败时的错误信息
    boolean truncated    // 输出是否被截断（超过 8KB）
) {}
```

---

## 4. 事件契约

### 4.1 新增事件类型

| 事件类型 | 触发时机 | 关键字段 |
|---|---|---|
| `MODEL_OUTPUT` | 每轮 LLM 返回后 | `content`, `turnIndex`, `hasToolCalls` |
| `TOOL_CALL` | 工具调用前 | `toolName`, `args`, `turnIndex`, `toolCallId` |
| `TOOL_RESULT` | 工具执行后 | `toolCallId`, `success`, `output`, `error`, `durationMs` |
| `TOOL_CONFIRM_REQUIRED` | 需用户确认时 | `toolName`, `args`, `reason` |
| `TOOL_CONFIRM_RESPONSE` | 用户响应确认 | `approved`, `toolCallId` |
| `RUN_COMPLETED` | loop 正常结束 | `totalTurns`, `totalDurationMs`, `reason` |
| `RUN_TERMINATED` | 超限/异常结束 | `reason`, `totalTurns`, `fallbackMessage` |

### 4.2 保留现有事件（不变）

`USER_MESSAGE`、`MODEL_MESSAGE`（最终答案）、`TOOL_CALL`（已有格式兼容）、`TOOL_RESULT`、`ERROR`、`MCP_TOOLS_BOUND`

---

## 5. 终止策略

```java
public record TerminationPolicy(
    int maxTurns,              // 默认 10
    long maxDurationMs,        // 默认 120_000（2 分钟）
    int maxConsecutiveErrors   // 默认 3
) {}
```

终止原因：

- `FINAL_ANSWER`：模型不再调工具，正常结束
- `MAX_TURNS`：达到最大轮次，输出当前最佳答案 + 提示
- `TIMEOUT`：超过最大时长，同上
- `CONSECUTIVE_ERRORS`：连续工具失败，告知用户
- `USER_ABORT`：用户主动终止（后期实现）

---

## 6. 回退计划

实施前先执行 git 回退，将 Codex 的 3 次提交（`4fc9914`、`b334e89`、`fbea207`）从当前分支撤销，回到 `b444859`（仅含设计文档的干净基线）。

回退后验证：
- `mvn test` 全部通过
- 基本对话（"你好"）正常返回，不触发多轮 loop

---

## 7. 前端适配

### 7.1 新增 SSE 事件展示

- `TOOL_CALL`：时间线显示"正在执行：`git clone ...`"
- `TOOL_RESULT`：显示执行结果摘要（截断到 200 字符）
- `TOOL_CONFIRM_REQUIRED`：展示确认卡片，用户点击"允许"/"拒绝"
- `RUN_COMPLETED` / `RUN_TERMINATED`：状态面板更新

### 7.2 用户确认交互（前端确认卡片）

**流程**：

```
ReactAgentService 遇到需确认工具
  → 落库 TOOL_CONFIRM_REQUIRED 事件
  → SSE 推送 TOOL_CONFIRM_REQUIRED（含 toolName、args、toolCallId）
  → 前端渲染确认卡片，显示命令详情 + [允许] / [跳过] 按钮
  → 用户点击
  → 前端 POST /api/runs/{runId}/tool-confirm { toolCallId, approved }
  → 后端 ToolConfirmRegistry 解除对应 CompletableFuture
  → ReactAgentService 恢复执行，根据 approved 决定执行或跳过
```

**后端挂起机制（ToolConfirmRegistry）**：

```java
// 伪代码
public class ToolConfirmRegistry {
    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    public boolean awaitConfirm(String toolCallId, long timeoutMs) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(toolCallId, future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return false; // 超时视为拒绝
        } finally {
            pending.remove(toolCallId);
        }
    }

    public void resolve(String toolCallId, boolean approved) {
        CompletableFuture<Boolean> future = pending.get(toolCallId);
        if (future != null) future.complete(approved);
    }
}
```

- 挂起超时默认 **5 分钟**，超时视为用户跳过该工具。
- `ReactAgentService` 运行在独立线程（Spring 异步或虚拟线程），阻塞等待不影响 web 容器。
- 新增 `ToolConfirmController`，暴露 `POST /api/runs/{runId}/tool-confirm` 接口。

### 7.3 不变部分

`ChatPanel` 的消息流、`MODEL_MESSAGE` 最终答案渲染保持不变。

---

## 8. 测试计划

### 8.1 后端单元测试

- `ReactAgentService`：mock chatModel，验证 loop 轮次控制、终止策略
- `ToolDispatcher`：白名单判断、确认逻辑
- `ShellTool`：命令黑名单拦截
- `FileTool`：路径安全检查（不允许越出工作目录）

### 8.2 手工回归场景

1. 简单问答（"你好"）：1 轮结束，无工具调用
2. fetch 场景（给 GitHub URL）：自动 fetch + 模型分析，无需确认
3. clone + 读文件：git clone 自动执行，文件读取自动，模型返回配置建议
4. 写文件场景：触发确认卡片，用户批准后写入
5. 连续工具失败：3 次后终止，输出友好说明

---

## 9. 实施顺序

1. **git 回退**：撤销 Codex 的 3 次提交（`4fc9914`、`b334e89`、`fbea207`），回到 `b444859` 干净基线，跑 `mvn test` 确认绿
2. **事件类型扩展**：在 `RunEventType` 新增 `MODEL_OUTPUT`、`TOOL_CONFIRM_REQUIRED`、`TOOL_CONFIRM_RESPONSE`、`RUN_COMPLETED`、`RUN_TERMINATED`
3. **ToolConfirmRegistry**：`CompletableFuture` 注册表，单例 Bean
4. **工具层**：`AgentTool` 接口 + `ShellTool`（白名单/黑名单）+ `FileTool` + `FetchTool`（适配现有实现）
5. **ToolDispatcher**：分级路由，白名单自动，需确认则调 `ToolConfirmRegistry.awaitConfirm()`
6. **ReactAgentService**：核心 ReAct loop，串联 LLM 流式调用 + ToolDispatcher + 事件落库
7. **ToolConfirmController**：`POST /api/runs/{runId}/tool-confirm`，调 `ToolConfirmRegistry.resolve()`
8. **AgentOrchestrator 瘦身**：移除旧 loop 控制流，保留能力方法（buildPromptMessages、appendEvent 等）
9. **前端适配**：`eventMapper` 新事件摘要、`ToolConfirmCard` 组件、时间线工具条目
10. **联调验收**：按手工回归场景逐一验证
