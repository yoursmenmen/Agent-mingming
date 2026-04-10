# ReAct Agent Loop 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现透明可观测的 ReAct Agent：每一轮 LLM 调用、工具调用、工具结果全部落库，写操作前弹前端确认卡片。

**Architecture:** `ReactAgentService` 持有显式 loop（chatModel 直调，非 ChatClient 自动执行），每轮产出事件后由 `ToolDispatcher` 路由工具调用，写操作通过 `ToolConfirmRegistry`（CompletableFuture）挂起线程等待前端确认。

**Tech Stack:** Spring Boot 3.3, Spring AI Alibaba 1.1.2.2 (DashScope), Vue 3 + TypeScript, Java 21

---

## 文件清单

### 后端新增
| 文件 | 职责 |
|------|------|
| `react/ReactAgentService.java` | ReAct 主循环，管理轮次、终止、事件落库 |
| `react/TerminationPolicy.java` | 终止策略 record（maxTurns/maxDurationMs/maxConsecutiveErrors） |
| `react/tool/AgentTool.java` | 工具接口（name/description/inputSchema/execute） |
| `react/tool/ToolResult.java` | 工具执行结果 record（success/output/error/truncated） |
| `react/tool/ToolDispatcher.java` | 工具路由：白名单自动/需确认挂起/黑名单拒绝 |
| `react/tool/ToolConfirmRegistry.java` | CompletableFuture 注册表，await/resolve |
| `react/tool/ShellTool.java` | Shell 命令执行，白名单+黑名单判断 |
| `react/tool/FileTool.java` | 文件读/写/删除，路径安全检查 |
| `react/tool/FetchTool.java` | HTTP 抓取页面，适配现有 fetch_page 逻辑 |
| `controller/ToolConfirmController.java` | `POST /api/runs/{runId}/tool-confirm` |

### 后端修改
| 文件 | 改动 |
|------|------|
| `event/RunEventType.java` | 新增 `MODEL_OUTPUT`, `TOOL_CONFIRM_REQUIRED`, `TOOL_CONFIRM_RESPONSE`, `RUN_COMPLETED`, `RUN_TERMINATED`；移除 Codex 遗留类型（回退后已不存在） |
| `orchestrator/AgentOrchestrator.java` | 移除 `executeSingleTurn` 和 loop 控制流，保留 `buildPromptMessages`、`appendEvent`、`startRun`、`runOnce` |
| `controller/ChatController.java` | 调用链：`startRun → ReactAgentService.execute`（替换 `executeSingleTurn`） |

### 后端删除（Codex 遗留，回退后已不存在，仅备注）
`orchestrator/loop/*`, `orchestrator/turn/*`, `event/contract/Loop*EventContract.java`

### 前端新增
| 文件 | 职责 |
|------|------|
| `components/ToolConfirmCard.vue` | 工具确认卡片（显示命令详情 + 允许/跳过按钮） |

### 前端修改
| 文件 | 改动 |
|------|------|
| `types/run.ts` | 新增 `ToolConfirmPayload` 类型 |
| `services/api.ts` | 新增 `postToolConfirm(runId, toolCallId, approved)` |
| `services/eventMapper.ts` | 新增 `TOOL_CONFIRM_REQUIRED`/`RUN_COMPLETED`/`RUN_TERMINATED` 摘要映射 |
| `composables/useChatConsole.ts` | 处理 `TOOL_CONFIRM_REQUIRED` SSE 事件，维护 `pendingToolConfirm` 状态 |
| `components/TimelinePanel.vue` | 在工具确认事件处渲染 `ToolConfirmCard` |

---

## Task 1：Git 回退 + 验证基线

**Files:** 无新建，仅 git 操作

- [ ] **Step 1: 执行回退（撤销 Codex 3 次提交）**

```bash
cd C:/DevApp/MyResp/MyJavaProject/Agent_mm
git revert --no-commit fbea207 b334e89 4fc9914
git commit -m "revert: 撤销 Codex agent loop 改造，回到 b444859 干净基线"
```

- [ ] **Step 2: 确认 RunEventType 回到原始状态**

读取 `backend/src/main/java/com/mingming/agent/event/RunEventType.java`，确认枚举值仅包含：
`USER_MESSAGE, MODEL_DELTA, MODEL_MESSAGE, RETRIEVAL_RESULT, RAG_SYNC, MCP_TOOLS_BOUND, MCP_CONFIRM_RESULT, TOOL_CALL, TOOL_RESULT, ERROR`

- [ ] **Step 3: 运行后端测试**

```bash
cd backend
export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH"
mvn test -q 2>&1 | tail -20
```
预期：BUILD SUCCESS，无 FAIL

- [ ] **Step 4: 确认 Codex loop 文件已移除**

```bash
ls backend/src/main/java/com/mingming/agent/orchestrator/loop/ 2>&1
ls backend/src/main/java/com/mingming/agent/orchestrator/turn/ 2>&1
```
预期：目录不存在或为空（若 revert 未删目录，手动 `git rm -r`）

---

## Task 2：扩展 RunEventType

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/event/RunEventType.java`
- Test: `backend/src/test/java/com/mingming/agent/event/RunEventTypeTest.java`

- [ ] **Step 1: 写失败测试**

新建 `backend/src/test/java/com/mingming/agent/event/RunEventTypeTest.java`：

```java
package com.mingming.agent.event;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class RunEventTypeTest {

    @Test
    void reactEventTypesExist() {
        assertThatNoException().isThrownBy(() -> RunEventType.valueOf("MODEL_OUTPUT"));
        assertThatNoException().isThrownBy(() -> RunEventType.valueOf("TOOL_CONFIRM_REQUIRED"));
        assertThatNoException().isThrownBy(() -> RunEventType.valueOf("TOOL_CONFIRM_RESPONSE"));
        assertThatNoException().isThrownBy(() -> RunEventType.valueOf("RUN_COMPLETED"));
        assertThatNoException().isThrownBy(() -> RunEventType.valueOf("RUN_TERMINATED"));
    }

    @Test
    void originalEventTypesStillExist() {
        assertThat(RunEventType.USER_MESSAGE).isNotNull();
        assertThat(RunEventType.TOOL_CALL).isNotNull();
        assertThat(RunEventType.TOOL_RESULT).isNotNull();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd backend
export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH"
mvn -Dtest=RunEventTypeTest test 2>&1 | grep -E "FAIL|ERROR|Tests run"
```
预期：FAIL（MODEL_OUTPUT 不存在）

- [ ] **Step 3: 修改 RunEventType，添加新类型**

```java
package com.mingming.agent.event;

public enum RunEventType {
    USER_MESSAGE,
    MODEL_DELTA,
    MODEL_MESSAGE,
    MODEL_OUTPUT,            // ReAct 每轮 LLM 输出（中间轮）
    RETRIEVAL_RESULT,
    RAG_SYNC,
    MCP_TOOLS_BOUND,
    MCP_CONFIRM_RESULT,
    TOOL_CALL,
    TOOL_RESULT,
    TOOL_CONFIRM_REQUIRED,   // 需要用户确认
    TOOL_CONFIRM_RESPONSE,   // 用户确认结果
    RUN_COMPLETED,           // ReAct loop 正常结束
    RUN_TERMINATED,          // 超限/异常结束
    ERROR
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn -Dtest=RunEventTypeTest test 2>&1 | grep -E "BUILD|Tests run"
```
预期：BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/mingming/agent/event/RunEventType.java \
        backend/src/test/java/com/mingming/agent/event/RunEventTypeTest.java
git commit -m "feat: 扩展 RunEventType，添加 ReAct Agent 事件类型"
```

---

## Task 3：ToolResult + AgentTool 接口

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/react/tool/ToolResult.java`
- Create: `backend/src/main/java/com/mingming/agent/react/tool/AgentTool.java`
- Test: `backend/src/test/java/com/mingming/agent/react/tool/ToolResultTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.mingming.agent.react.tool;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTest {

    @Test
    void successResult_hasCorrectFields() {
        ToolResult result = ToolResult.success("hello output");
        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("hello output");
        assertThat(result.error()).isNull();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void errorResult_hasCorrectFields() {
        ToolResult result = ToolResult.error("something went wrong");
        assertThat(result.success()).isFalse();
        assertThat(result.output()).isNull();
        assertThat(result.error()).isEqualTo("something went wrong");
    }

    @Test
    void toJson_includesSuccessField() {
        ToolResult result = ToolResult.success("data");
        String json = result.toJson();
        assertThat(json).contains("\"success\":true");
        assertThat(json).contains("\"output\":\"data\"");
    }

    @Test
    void skippedResult() {
        ToolResult result = ToolResult.skipped("用户拒绝执行");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("用户拒绝");
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

```bash
mvn -Dtest=ToolResultTest test 2>&1 | grep -E "ERROR|cannot find"
```

- [ ] **Step 3: 创建 ToolResult**

```java
package com.mingming.agent.react.tool;

public record ToolResult(
        boolean success,
        String output,
        String error,
        boolean truncated) {

    private static final int MAX_OUTPUT_CHARS = 8192;

    public static ToolResult success(String output) {
        if (output != null && output.length() > MAX_OUTPUT_CHARS) {
            return new ToolResult(true, output.substring(0, MAX_OUTPUT_CHARS), null, true);
        }
        return new ToolResult(true, output, null, false);
    }

    public static ToolResult error(String error) {
        return new ToolResult(false, null, error, false);
    }

    public static ToolResult skipped(String reason) {
        return new ToolResult(false, null, reason != null ? reason : "用户跳过执行", false);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"success\":").append(success);
        if (output != null) {
            sb.append(",\"output\":").append(jsonString(output));
        }
        if (error != null) {
            sb.append(",\"error\":").append(jsonString(error));
        }
        if (truncated) {
            sb.append(",\"truncated\":true");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
```

- [ ] **Step 4: 创建 AgentTool 接口**

```java
package com.mingming.agent.react.tool;

import java.util.Map;

public interface AgentTool {

    /** 工具名称，对应模型调用时的 function name */
    String name();

    /** 工具描述，用于生成 function schema */
    String description();

    /**
     * 工具参数 JSON Schema（OpenAI function calling 格式）
     * 例：{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}
     */
    String inputSchema();

    /** 执行工具，args 为模型传入的参数 */
    ToolResult execute(Map<String, Object> args);
}
```

- [ ] **Step 5: 运行测试**

```bash
mvn -Dtest=ToolResultTest test 2>&1 | grep -E "BUILD|Tests run"
```
预期：BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/mingming/agent/react/ \
        backend/src/test/java/com/mingming/agent/react/
git commit -m "feat: 新增 AgentTool 接口和 ToolResult record"
```

---

## Task 4：ToolConfirmRegistry + ToolConfirmController

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/react/tool/ToolConfirmRegistry.java`
- Create: `backend/src/main/java/com/mingming/agent/controller/ToolConfirmController.java`
- Test: `backend/src/test/java/com/mingming/agent/react/tool/ToolConfirmRegistryTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.mingming.agent.react.tool;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.assertj.core.api.Assertions.assertThat;

class ToolConfirmRegistryTest {

    @Test
    void resolve_approved_returnsTrue() throws Exception {
        ToolConfirmRegistry registry = new ToolConfirmRegistry();
        Future<Boolean> future = Executors.newSingleThreadExecutor().submit(
                () -> registry.awaitConfirm("call-1", 5_000L));
        Thread.sleep(50);
        registry.resolve("call-1", true);
        assertThat(future.get()).isTrue();
    }

    @Test
    void resolve_rejected_returnsFalse() throws Exception {
        ToolConfirmRegistry registry = new ToolConfirmRegistry();
        Future<Boolean> future = Executors.newSingleThreadExecutor().submit(
                () -> registry.awaitConfirm("call-2", 5_000L));
        Thread.sleep(50);
        registry.resolve("call-2", false);
        assertThat(future.get()).isFalse();
    }

    @Test
    void timeout_returnsFalse() {
        ToolConfirmRegistry registry = new ToolConfirmRegistry();
        boolean result = registry.awaitConfirm("call-timeout", 100L);
        assertThat(result).isFalse();
    }

    @Test
    void resolveUnknownId_doesNotThrow() {
        ToolConfirmRegistry registry = new ToolConfirmRegistry();
        registry.resolve("nonexistent", true); // should not throw
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -Dtest=ToolConfirmRegistryTest test 2>&1 | grep -E "ERROR|cannot find"
```

- [ ] **Step 3: 创建 ToolConfirmRegistry**

```java
package com.mingming.agent.react.tool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
public class ToolConfirmRegistry {

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    /**
     * 挂起当前线程等待用户确认，超时返回 false（视为跳过）。
     * @param toolCallId 唯一标识本次工具调用
     * @param timeoutMs  等待超时毫秒，建议 300_000（5 分钟）
     */
    public boolean awaitConfirm(String toolCallId, long timeoutMs) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(toolCallId, future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            pending.remove(toolCallId);
        }
    }

    /**
     * 由 ToolConfirmController 调用，解除挂起。
     * @param toolCallId 对应 awaitConfirm 传入的 toolCallId
     * @param approved   true=允许，false=跳过
     */
    public void resolve(String toolCallId, boolean approved) {
        CompletableFuture<Boolean> future = pending.get(toolCallId);
        if (future != null) {
            future.complete(approved);
        }
    }

    public boolean hasPending(String toolCallId) {
        return pending.containsKey(toolCallId);
    }
}
```

- [ ] **Step 4: 创建 ToolConfirmController**

```java
package com.mingming.agent.controller;

import com.mingming.agent.react.tool.ToolConfirmRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class ToolConfirmController {

    private final ToolConfirmRegistry toolConfirmRegistry;

    public record ToolConfirmRequest(String toolCallId, boolean approved) {}
    public record ToolConfirmResponse(String toolCallId, boolean resolved) {}

    @PostMapping("/api/runs/{runId}/tool-confirm")
    public ResponseEntity<ToolConfirmResponse> confirm(
            @PathVariable String runId,
            @RequestBody ToolConfirmRequest req) {
        if (req.toolCallId() == null || req.toolCallId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        toolConfirmRegistry.resolve(req.toolCallId(), req.approved());
        return ResponseEntity.ok(new ToolConfirmResponse(req.toolCallId(), true));
    }
}
```

- [ ] **Step 5: 运行测试**

```bash
mvn -Dtest=ToolConfirmRegistryTest test 2>&1 | grep -E "BUILD|Tests run"
```
预期：BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/mingming/agent/react/tool/ToolConfirmRegistry.java \
        backend/src/main/java/com/mingming/agent/controller/ToolConfirmController.java \
        backend/src/test/java/com/mingming/agent/react/tool/ToolConfirmRegistryTest.java
git commit -m "feat: 新增 ToolConfirmRegistry 和 ToolConfirmController"
```

---

## Task 5：ShellTool

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/react/tool/ShellTool.java`
- Test: `backend/src/test/java/com/mingming/agent/react/tool/ShellToolTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.mingming.agent.react.tool;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ShellToolTest {

    private final ShellTool shellTool = new ShellTool();

    @Test
    void name_isShellExec() {
        assertThat(shellTool.name()).isEqualTo("shell_exec");
    }

    @Test
    void blacklistedCommand_returnsError() {
        ToolResult result = shellTool.execute(Map.of("command", "rm -rf /"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("禁止");
    }

    @Test
    void anotherBlacklistedCommand_returnsError() {
        ToolResult result = shellTool.execute(Map.of("command", "shutdown -h now"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("禁止");
    }

    @Test
    void missingCommand_returnsError() {
        ToolResult result = shellTool.execute(Map.of());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("command");
    }

    @Test
    void whitelistedEchoCommand_executes() {
        ToolResult result = shellTool.execute(Map.of("command", "echo hello"));
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("hello");
    }

    @Test
    void isWhitelisted_detectsGitClone() {
        assertThat(ShellTool.isWhitelisted("git clone https://github.com/foo/bar")).isTrue();
    }

    @Test
    void isWhitelisted_detectsLs() {
        assertThat(ShellTool.isWhitelisted("ls -la")).isTrue();
    }

    @Test
    void isWhitelisted_npmInstallIsNot() {
        assertThat(ShellTool.isWhitelisted("npm install")).isFalse();
    }

    @Test
    void isBlacklisted_rmrf() {
        assertThat(ShellTool.isBlacklisted("rm -rf /")).isTrue();
    }

    @Test
    void isBlacklisted_normalRmIsNot() {
        // 普通 rm 不是黑名单，但不在白名单，需要确认
        assertThat(ShellTool.isBlacklisted("rm somefile.txt")).isFalse();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -Dtest=ShellToolTest test 2>&1 | grep -E "ERROR|cannot find"
```

- [ ] **Step 3: 创建 ShellTool**

```java
package com.mingming.agent.react.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ShellTool implements AgentTool {

    // 白名单前缀：匹配则自动执行，无需确认
    private static final List<String> WHITELIST_PREFIXES = List.of(
            "ls", "ll", "pwd", "echo", "cat", "head", "tail", "which", "type",
            "git clone", "git status", "git log", "git diff", "git fetch",
            "node --version", "node -v", "npm --version", "npm -v",
            "java --version", "java -version", "mvn --version", "mvn -v",
            "python --version", "python3 --version", "pip --version",
            "find", "tree", "wc", "sort", "uniq", "grep", "awk", "sed",
            "curl --version", "wget --version", "docker --version",
            "uname", "hostname", "whoami", "date", "uptime", "env");

    // 黑名单模式：匹配则直接拒绝
    private static final List<String> BLACKLIST_PATTERNS = List.of(
            "rm -rf /", "rm -rf /*", "rm -rf ~",
            "mkfs", "format", "shutdown", "reboot", "halt", "poweroff",
            "dd if=/dev/zero", "dd if=/dev/random",
            "> /dev/sda", "> /dev/hda",
            ":(){ :|:& };:", // fork bomb
            "chmod -R 777 /", "chown -R");

    @Override
    public String name() {
        return "shell_exec";
    }

    @Override
    public String description() {
        return "执行 shell 命令。安全命令（ls、git clone 等）自动执行；npm install、文件写操作等需用户确认；危险命令直接拒绝。";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "command": {
                      "type": "string",
                      "description": "要执行的 shell 命令"
                    },
                    "workDir": {
                      "type": "string",
                      "description": "命令工作目录（可选，默认系统临时目录）"
                    }
                  },
                  "required": ["command"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object cmdObj = args.get("command");
        if (!(cmdObj instanceof String command) || command.isBlank()) {
            return ToolResult.error("缺少 command 参数");
        }
        if (isBlacklisted(command)) {
            return ToolResult.error("禁止执行该命令（黑名单策略）：" + command);
        }
        String workDir = args.getOrDefault("workDir", System.getProperty("java.io.tmpdir")).toString();
        return runCommand(command, workDir);
    }

    public static boolean isWhitelisted(String command) {
        if (command == null) return false;
        String trimmed = command.trim().toLowerCase();
        for (String prefix : WHITELIST_PREFIXES) {
            if (trimmed.equals(prefix) || trimmed.startsWith(prefix + " ") || trimmed.startsWith(prefix + "\t")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlacklisted(String command) {
        if (command == null) return false;
        String trimmed = command.trim().toLowerCase();
        for (String pattern : BLACKLIST_PATTERNS) {
            if (trimmed.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        // sudo 一律需确认（黑名单不包含，但 isWhitelisted 也不包含）
        return false;
    }

    private ToolResult runCommand(String command, String workDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            // Windows/Unix 兼容
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("bash", "-c", command);
            }
            pb.directory(new java.io.File(workDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("命令执行超时（60 秒）");
            }

            int exitCode = process.exitValue();
            String out = output.toString().trim();
            if (exitCode == 0) {
                return ToolResult.success(out.isBlank() ? "(命令执行成功，无输出)" : out);
            } else {
                return ToolResult.error("exitCode=" + exitCode + (out.isBlank() ? "" : "\n" + out));
            }
        } catch (Exception e) {
            return ToolResult.error("执行异常：" + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn -Dtest=ShellToolTest test 2>&1 | grep -E "BUILD|Tests run"
```
预期：BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/mingming/agent/react/tool/ShellTool.java \
        backend/src/test/java/com/mingming/agent/react/tool/ShellToolTest.java
git commit -m "feat: 新增 ShellTool（白名单自动/黑名单拒绝）"
```

---

## Task 6：FileTool

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/react/tool/FileTool.java`
- Test: `backend/src/test/java/com/mingming/agent/react/tool/FileToolTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.mingming.agent.react.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FileToolTest {

    @TempDir
    Path tempDir;

    @Test
    void readExistingFile_returnsContent() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");
        FileTool tool = new FileTool();
        ToolResult result = tool.execute(Map.of("action", "read", "path", file.toString()));
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("hello world");
    }

    @Test
    void readMissingFile_returnsError() {
        FileTool tool = new FileTool();
        ToolResult result = tool.execute(Map.of("action", "read", "path", "/nonexistent/file.txt"));
        assertThat(result.success()).isFalse();
    }

    @Test
    void writeFile_createsContent() throws IOException {
        Path file = tempDir.resolve("out.txt");
        FileTool tool = new FileTool();
        ToolResult result = tool.execute(Map.of("action", "write", "path", file.toString(), "content", "written!"));
        assertThat(result.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("written!");
    }

    @Test
    void deleteFile_removesIt() throws IOException {
        Path file = tempDir.resolve("del.txt");
        Files.writeString(file, "bye");
        FileTool tool = new FileTool();
        ToolResult result = tool.execute(Map.of("action", "delete", "path", file.toString()));
        assertThat(result.success()).isTrue();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void missingAction_returnsError() {
        FileTool tool = new FileTool();
        ToolResult result = tool.execute(Map.of("path", "/some/path"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("action");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn -Dtest=FileToolTest test 2>&1 | grep -E "ERROR|cannot find"
```

- [ ] **Step 3: 创建 FileTool**

```java
package com.mingming.agent.react.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class FileTool implements AgentTool {

    @Override
    public String name() {
        return "file_op";
    }

    @Override
    public String description() {
        return "文件操作：读取（read）、写入（write）、删除（delete）。读操作自动执行；写入和删除需要用户确认。";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["read", "write", "delete"],
                      "description": "操作类型"
                    },
                    "path": {
                      "type": "string",
                      "description": "文件绝对路径"
                    },
                    "content": {
                      "type": "string",
                      "description": "写入时的文件内容（action=write 时必填）"
                    }
                  },
                  "required": ["action", "path"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object actionObj = args.get("action");
        Object pathObj = args.get("path");
        if (!(actionObj instanceof String action) || action.isBlank()) {
            return ToolResult.error("缺少 action 参数（read/write/delete）");
        }
        if (!(pathObj instanceof String pathStr) || pathStr.isBlank()) {
            return ToolResult.error("缺少 path 参数");
        }
        Path path = Path.of(pathStr);
        return switch (action) {
            case "read" -> readFile(path);
            case "write" -> writeFile(path, args.getOrDefault("content", "").toString());
            case "delete" -> deleteFile(path);
            default -> ToolResult.error("未知 action：" + action + "，支持 read/write/delete");
        };
    }

    private ToolResult readFile(Path path) {
        try {
            if (!Files.exists(path)) {
                return ToolResult.error("文件不存在：" + path);
            }
            String content = Files.readString(path);
            return ToolResult.success(content);
        } catch (IOException e) {
            return ToolResult.error("读取文件失败：" + e.getMessage());
        }
    }

    private ToolResult writeFile(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            return ToolResult.success("文件已写入：" + path + "（" + content.length() + " 字节）");
        } catch (IOException e) {
            return ToolResult.error("写入文件失败：" + e.getMessage());
        }
    }

    private ToolResult deleteFile(Path path) {
        try {
            if (!Files.exists(path)) {
                return ToolResult.error("文件不存在：" + path);
            }
            Files.delete(path);
            return ToolResult.success("文件已删除：" + path);
        } catch (IOException e) {
            return ToolResult.error("删除文件失败：" + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn -Dtest=FileToolTest test 2>&1 | grep -E "BUILD|Tests run"
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/mingming/agent/react/tool/FileTool.java \
        backend/src/test/java/com/mingming/agent/react/tool/FileToolTest.java
git commit -m "feat: 新增 FileTool（read/write/delete）"
```

---

## Task 7：FetchTool

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/react/tool/FetchTool.java`
- Test: `backend/src/test/java/com/mingming/agent/react/tool/FetchToolTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.mingming.agent.react.tool;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FetchToolTest {

    @Test
    void name_isFetchPage() {
        FetchTool tool = new FetchTool();
        assertThat(tool.name()).isEqualTo("fetch_page");
    }

    @Test
    void missingUrl_returnsError() {
        FetchTool tool = new FetchTool();
        ToolResult result = tool.execute(Map.of());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("url");
    }

    @Test
    void invalidUrl_returnsError() {
        FetchTool tool = new FetchTool();
        ToolResult result = tool.execute(Map.of("url", "not-a-url"));
        assertThat(result.success()).isFalse();
    }
}
```

- [ ] **Step 2: 创建 FetchTool**

```java
package com.mingming.agent.react.tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class FetchTool implements AgentTool {

    private static final int TIMEOUT_SECONDS = 30;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; AgentBot/1.0)";

    @Override
    public String name() {
        return "fetch_page";
    }

    @Override
    public String description() {
        return "获取指定 URL 的网页内容（文本）。适用于阅读 GitHub README、文档页面、API 参考等。";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "url": {
                      "type": "string",
                      "description": "要获取的网页 URL"
                    }
                  },
                  "required": ["url"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object urlObj = args.get("url");
        if (!(urlObj instanceof String url) || url.isBlank()) {
            return ToolResult.error("缺少 url 参数");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
            if (!uri.isAbsolute()) {
                return ToolResult.error("URL 必须是绝对路径（以 http:// 或 https:// 开头）");
            }
        } catch (IllegalArgumentException e) {
            return ToolResult.error("无效 URL：" + e.getMessage());
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body();
                // 简单去除 HTML 标签，保留文本（原始实现的 strip 逻辑）
                String text = stripHtmlTags(body);
                return ToolResult.success(text);
            } else {
                return ToolResult.error("HTTP " + response.statusCode() + "：" + url);
            }
        } catch (Exception e) {
            return ToolResult.error("请求失败：" + e.getMessage());
        }
    }

    private String stripHtmlTags(String html) {
        if (html == null) return "";
        // 移除 <script>/<style> 块
        String cleaned = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")        // 移除所有标签
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("\\s{3,}", "\n")       // 合并多余空行
                .trim();
        return cleaned;
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
mvn -Dtest=FetchToolTest test 2>&1 | grep -E "BUILD|Tests run"
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/mingming/agent/react/tool/FetchTool.java \
        backend/src/test/java/com/mingming/agent/react/tool/FetchToolTest.java
git commit -m "feat: 新增 FetchTool（HTTP 抓取页面）"
```

---

## Task 8：ToolDispatcher

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/react/tool/ToolDispatcher.java`
- Test: `backend/src/test/java/com/mingming/agent/react/tool/ToolDispatcherTest.java`

ToolDispatcher 负责：①识别工具 ②判断是否需要确认 ③调用 ToolConfirmRegistry 挂起 ④执行工具

- [ ] **Step 1: 写失败测试**

```java
package com.mingming.agent.react.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;

class ToolDispatcherTest {

    private final ToolConfirmRegistry registry = new ToolConfirmRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 自动执行工具（fetch_page）
    private final AgentTool fetchTool = new AgentTool() {
        public String name() { return "fetch_page"; }
        public String description() { return "fetch"; }
        public String inputSchema() { return "{}"; }
        public ToolResult execute(Map<String, Object> args) {
            return ToolResult.success("page content");
        }
    };

    @Test
    void whitelistTool_executesAutomatically() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(fetchTool), registry, objectMapper);
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "tc-1", "function", "fetch_page", "{\"url\":\"https://example.com\"}");
        Consumer<String> sse = data -> {};
        ToolResult result = dispatcher.dispatch(UUID.randomUUID(), toolCall, sse);
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("page content");
    }

    @Test
    void unknownTool_returnsError() {
        ToolDispatcher dispatcher = new ToolDispatcher(
                List.of(fetchTool), registry, objectMapper);
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "tc-2", "function", "unknown_tool", "{}");
        ToolResult result = dispatcher.dispatch(UUID.randomUUID(), toolCall, data -> {});
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("未找到工具");
    }
}
```

- [ ] **Step 2: 创建 ToolDispatcher**

```java
package com.mingming.agent.react.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

@Component
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);
    private static final long CONFIRM_TIMEOUT_MS = 300_000L; // 5 分钟

    private final Map<String, AgentTool> toolsByName;
    private final ToolConfirmRegistry confirmRegistry;
    private final ObjectMapper objectMapper;

    public ToolDispatcher(List<AgentTool> tools, ToolConfirmRegistry confirmRegistry, ObjectMapper objectMapper) {
        this.confirmRegistry = confirmRegistry;
        this.objectMapper = objectMapper;
        this.toolsByName = new HashMap<>();
        for (AgentTool tool : tools) {
            toolsByName.put(tool.name(), tool);
        }
    }

    /**
     * 路由并执行工具调用。
     * - 未知工具 → ToolResult.error
     * - 黑名单命令 → ToolResult.error（ShellTool 内部处理）
     * - 需要确认 → SSE 推送 TOOL_CONFIRM_REQUIRED，挂起等待
     * - 白名单/通过确认 → 直接执行
     */
    public ToolResult dispatch(UUID runId, AssistantMessage.ToolCall toolCall, Consumer<String> sseConsumer) {
        String toolName = toolCall.name();
        AgentTool tool = toolsByName.get(toolName);
        if (tool == null) {
            return ToolResult.error("未找到工具：" + toolName);
        }

        Map<String, Object> args = parseArgs(toolCall.arguments());

        if (requiresConfirmation(tool, args)) {
            // 推送确认请求
            String confirmPayload = buildConfirmPayload(toolCall.id(), toolName, args);
            sseConsumer.accept(confirmPayload);
            log.info("等待用户确认工具调用 runId={} tool={} toolCallId={}", runId, toolName, toolCall.id());

            boolean approved = confirmRegistry.awaitConfirm(toolCall.id(), CONFIRM_TIMEOUT_MS);
            if (!approved) {
                return ToolResult.skipped("用户跳过或超时未响应");
            }
        }

        try {
            return tool.execute(args);
        } catch (Exception e) {
            log.error("工具执行异常 tool={}", toolName, e);
            return ToolResult.error("工具执行异常：" + e.getMessage());
        }
    }

    /** 判断是否需要用户确认 */
    private boolean requiresConfirmation(AgentTool tool, Map<String, Object> args) {
        // file_op 的 write/delete 需要确认
        if ("file_op".equals(tool.name())) {
            String action = String.valueOf(args.getOrDefault("action", ""));
            return "write".equals(action) || "delete".equals(action);
        }
        // shell_exec：不在白名单的命令需要确认（黑名单在工具内部拒绝）
        if ("shell_exec".equals(tool.name())) {
            String command = String.valueOf(args.getOrDefault("command", ""));
            return !ShellTool.isWhitelisted(command);
        }
        // 其他工具（fetch_page 等）自动执行
        return false;
    }

    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argsJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("_raw", argsJson);
        }
    }

    private String buildConfirmPayload(String toolCallId, String toolName, Map<String, Object> args) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "TOOL_CONFIRM_REQUIRED");
            payload.put("toolCallId", toolCallId);
            payload.put("toolName", toolName);
            payload.put("args", args);
            payload.put("reason", buildConfirmReason(toolName, args));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"TOOL_CONFIRM_REQUIRED\",\"toolCallId\":\"" + toolCallId + "\"}";
        }
    }

    private String buildConfirmReason(String toolName, Map<String, Object> args) {
        if ("shell_exec".equals(toolName)) {
            return "即将执行命令：" + args.getOrDefault("command", "(未知)");
        }
        if ("file_op".equals(toolName)) {
            String action = String.valueOf(args.getOrDefault("action", ""));
            String path = String.valueOf(args.getOrDefault("path", "(未知)"));
            return ("delete".equals(action) ? "即将删除文件：" : "即将写入文件：") + path;
        }
        return "即将执行工具：" + toolName;
    }

    public List<AgentTool> getTools() {
        return List.copyOf(toolsByName.values());
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
mvn -Dtest=ToolDispatcherTest test 2>&1 | grep -E "BUILD|Tests run"
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/mingming/agent/react/tool/ToolDispatcher.java \
        backend/src/test/java/com/mingming/agent/react/tool/ToolDispatcherTest.java
git commit -m "feat: 新增 ToolDispatcher（分级确认路由）"
```

---

## Task 9：ReactAgentService（核心 ReAct Loop）

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/react/ReactAgentService.java`
- Create: `backend/src/main/java/com/mingming/agent/react/TerminationPolicy.java`
- Test: `backend/src/test/java/com/mingming/agent/react/ReactAgentServiceTest.java`

- [ ] **Step 1: 创建 TerminationPolicy**

```java
package com.mingming.agent.react;

public record TerminationPolicy(
        int maxTurns,
        long maxDurationMs,
        int maxConsecutiveErrors) {

    public static TerminationPolicy defaults() {
        return new TerminationPolicy(10, 120_000L, 3);
    }
}
```

- [ ] **Step 2: 写失败测试**

```java
package com.mingming.agent.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.orchestrator.AgentOrchestrator;
import com.mingming.agent.react.tool.AgentTool;
import com.mingming.agent.react.tool.ToolDispatcher;
import com.mingming.agent.react.tool.ToolConfirmRegistry;
import com.mingming.agent.react.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReactAgentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatModel mockChatModelFinalAnswer(String content) {
        ChatModel model = mock(ChatModel.class);
        AssistantMessage msg = new AssistantMessage(content, Map.of(), List.of());
        ChatResponse response = mock(ChatResponse.class);
        Generation gen = mock(Generation.class);
        when(response.getResult()).thenReturn(gen);
        when(gen.getOutput()).thenReturn(msg);
        when(model.stream(any())).thenReturn(Flux.just(response));
        return model;
    }

    @Test
    void simpleQuestion_oneRound_noPendingTools() {
        ChatModel chatModel = mockChatModelFinalAnswer("你好！有什么可以帮你的？");
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.buildPromptMessages(any(), anyString())).thenReturn(List.of());
        ToolDispatcher dispatcher = mock(ToolDispatcher.class);

        ReactAgentService service = new ReactAgentService(
                chatModel, orchestrator, dispatcher, objectMapper);

        List<String> sseEvents = new ArrayList<>();
        service.execute(
                UUID.randomUUID(), UUID.randomUUID(), "你好",
                TerminationPolicy.defaults(), sseEvents::add);

        // 至少有一个 SSE 事件包含内容
        assertThat(sseEvents).anyMatch(e -> e.contains("你好"));
        // 没有工具调用
        verify(dispatcher, never()).dispatch(any(), any(), any());
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

```bash
mvn -Dtest=ReactAgentServiceTest test 2>&1 | grep -E "ERROR|cannot find"
```

- [ ] **Step 4: 创建 ReactAgentService**

```java
package com.mingming.agent.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.event.RunEventType;
import com.mingming.agent.orchestrator.AgentOrchestrator;
import com.mingming.agent.react.tool.ToolDispatcher;
import com.mingming.agent.react.tool.ToolResult;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ReactAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentService.class);

    private static final String SYSTEM_PROMPT = """
            你是一个智能助手，可以使用工具完成复杂任务（如读取网页、执行命令、操作文件）。
            当需要信息或执行操作时，请直接调用对应工具。
            当你已能给出最终答案时，直接回答即可，无需任何特殊标记。
            若任务无法自动完成，请明确告知用户需要手动执行哪些步骤。
            """;

    private final ChatModel chatModel;
    private final AgentOrchestrator orchestrator;
    private final ToolDispatcher toolDispatcher;
    private final ObjectMapper objectMapper;

    public ReactAgentService(
            ChatModel chatModel,
            AgentOrchestrator orchestrator,
            ToolDispatcher toolDispatcher,
            ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.orchestrator = orchestrator;
        this.toolDispatcher = toolDispatcher;
        this.objectMapper = objectMapper;
    }

    public void execute(
            UUID runId,
            UUID sessionId,
            String userText,
            TerminationPolicy policy,
            Consumer<String> sseConsumer) {

        AtomicInteger seq = new AtomicInteger(1);
        long startedAt = System.currentTimeMillis();
        int consecutiveErrors = 0;

        // 构建初始消息列表
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.addAll(orchestrator.buildPromptMessages(sessionId, userText));

        // 记录用户消息事件
        appendEvent(runId, seq, RunEventType.USER_MESSAGE, Map.of("content", userText));

        for (int turn = 1; turn <= policy.maxTurns(); turn++) {
            // 检查超时
            if (System.currentTimeMillis() - startedAt > policy.maxDurationMs()) {
                String msg = "执行超时，共 " + (turn - 1) + " 轮。";
                appendEvent(runId, seq, RunEventType.RUN_TERMINATED,
                        Map.of("reason", "TIMEOUT", "totalTurns", turn - 1));
                sseConsumer.accept(jsonContent(msg));
                return;
            }

            log.info("ReAct turn={} runId={}", turn, runId);

            // 流式调用 LLM，同时累积完整响应
            StringBuilder contentBuilder = new StringBuilder();
            AssistantMessage finalAssistantMsg;
            try {
                ChatResponse lastResponse = callModelStreaming(
                        messages, contentBuilder, sseConsumer);
                finalAssistantMsg = lastResponse.getResult().getOutput();
            } catch (Exception e) {
                log.error("LLM 调用异常 turn={} runId={}", turn, runId, e);
                consecutiveErrors++;
                if (consecutiveErrors >= policy.maxConsecutiveErrors()) {
                    appendEvent(runId, seq, RunEventType.RUN_TERMINATED,
                            Map.of("reason", "CONSECUTIVE_ERRORS", "totalTurns", turn));
                    sseConsumer.accept(jsonContent("连续调用失败，已终止。错误：" + e.getMessage()));
                    return;
                }
                continue;
            }

            String assistantText = contentBuilder.toString();

            // 落库 MODEL_OUTPUT
            appendEvent(runId, seq, RunEventType.MODEL_OUTPUT,
                    Map.of("content", assistantText, "turnIndex", turn));

            List<AssistantMessage.ToolCall> toolCalls = finalAssistantMsg.getToolCalls();

            // 没有工具调用 → 最终答案
            if (toolCalls == null || toolCalls.isEmpty()) {
                appendEvent(runId, seq, RunEventType.RUN_COMPLETED,
                        Map.of("totalTurns", turn,
                                "totalDurationMs", System.currentTimeMillis() - startedAt));
                // MODEL_MESSAGE 落库（用于会话历史）
                appendEvent(runId, seq, RunEventType.MODEL_MESSAGE,
                        Map.of("content", assistantText));
                return;
            }

            // 执行工具调用
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            consecutiveErrors = 0;
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                // 落库 TOOL_CALL 事件
                appendEvent(runId, seq, RunEventType.TOOL_CALL, Map.of(
                        "tool", toolCall.name(),
                        "toolCallId", toolCall.id(),
                        "args", toolCall.arguments(),
                        "turnIndex", turn));

                // SSE 推送工具调用摘要
                sseConsumer.accept(jsonContent("🔧 调用工具：" + toolCall.name()));

                // 分派执行（含确认逻辑）
                ToolResult result = toolDispatcher.dispatch(runId, toolCall, sseConsumer);

                // 落库 TOOL_RESULT 事件
                appendEvent(runId, seq, RunEventType.TOOL_RESULT, Map.of(
                        "tool", toolCall.name(),
                        "toolCallId", toolCall.id(),
                        "success", result.success(),
                        "output", result.output() != null ? result.output() : "",
                        "error", result.error() != null ? result.error() : "",
                        "turnIndex", turn));

                // SSE 推送工具结果摘要
                String resultSummary = result.success()
                        ? "✅ " + toolCall.name() + " 执行成功"
                        : "❌ " + toolCall.name() + "：" + result.error();
                sseConsumer.accept(jsonContent(resultSummary));

                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolCall.name(), result.toJson()));
            }

            // 追加到消息历史，进入下一轮
            messages.add(new AssistantMessage(
                    assistantText, Map.of(), toolCalls));
            messages.add(new ToolResponseMessage(toolResponses));
        }

        // 达到最大轮次
        appendEvent(runId, seq, RunEventType.RUN_TERMINATED,
                Map.of("reason", "MAX_TURNS", "totalTurns", policy.maxTurns()));
        sseConsumer.accept(jsonContent(
                "已达到最大轮次（" + policy.maxTurns() + " 轮），任务未完成。请缩小问题范围后重试。"));
    }

    /**
     * 把 AgentTool 列表转为 Spring AI FunctionCallback，用于向模型声明可用工具。
     * 使用 ToolCallingChatOptions 禁用自动执行，让模型只返回 tool_calls 而不自动执行。
     * 注意：如果 Spring AI Alibaba 1.1.2.2 的 internalToolExecutionEnabled API 不可用，
     *       降级方案是把工具 schema 手动写入 system prompt（见 buildToolSchemaPrompt）。
     */
    private ChatOptions buildChatOptions() {
        try {
            // 首选：Spring AI 1.0+ ToolCallingChatOptions
            var callbacks = toolDispatcher.getTools().stream()
                    .map(tool -> org.springframework.ai.tool.function.FunctionCallback.builder()
                            .function(tool.name(), (java.util.function.Function<String, String>) args -> {
                                // 占位：实际调用由我们手动 dispatch，此处不会被调用
                                return "{}";
                            })
                            .description(tool.description())
                            .inputType(String.class)
                            .build())
                    .toList();
            return org.springframework.ai.chat.prompt.ToolCallingChatOptions.builder()
                    .toolCallbacks(callbacks)
                    .internalToolExecutionEnabled(false)
                    .build();
        } catch (Exception e) {
            // 降级：空选项，工具 schema 通过 buildToolSchemaSystemMessage() 注入 system prompt
            return org.springframework.ai.chat.prompt.ChatOptionsBuilder.builder().build();
        }
    }

    /** 当 ToolCallingChatOptions 不可用时，把工具 schema 注入到 system prompt 供模型参考 */
    private SystemMessage buildToolSchemaSystemMessage() {
        StringBuilder sb = new StringBuilder("可用工具列表（JSON function calling 格式）：\n");
        for (var tool : toolDispatcher.getTools()) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description())
              .append("\n  schema: ").append(tool.inputSchema()).append("\n");
        }
        return new SystemMessage(sb.toString());
    }

    private ChatResponse callModelStreaming(
            List<Message> messages,
            StringBuilder contentBuilder,
            Consumer<String> sseConsumer) {

        // 尝试带工具定义的调用；若 ToolCallingChatOptions 编译失败，退回无选项调用
        Prompt prompt;
        try {
            prompt = new Prompt(messages, buildChatOptions());
        } catch (Exception e) {
            // 降级：在消息里注入工具 schema
            List<Message> augmented = new ArrayList<>(messages);
            augmented.add(0, buildToolSchemaSystemMessage());
            prompt = new Prompt(augmented);
        }

        Flux<ChatResponse> stream = chatModel.stream(prompt);
        // 收集工具调用：tool_calls 在流的最后一个有效 chunk 中出现
        ChatResponse[] lastNonNullRef = new ChatResponse[1];
        stream.doOnNext(chunk -> {
            if (chunk == null || chunk.getResult() == null) return;
            lastNonNullRef[0] = chunk;
            AssistantMessage msg = chunk.getResult().getOutput();
            if (msg == null) return;
            String delta = msg.getText();
            if (delta != null && !delta.isBlank()) {
                contentBuilder.append(delta);
                sseConsumer.accept(jsonContent(delta));
            }
        }).blockLast();

        if (lastNonNullRef[0] == null) {
            throw new IllegalStateException("模型流式调用未返回任何数据");
        }
        return lastNonNullRef[0];
    }

    private void appendEvent(UUID runId, AtomicInteger seq, RunEventType type, Map<String, Object> data) {
        ObjectNode payload = objectMapper.createObjectNode();
        data.forEach((k, v) -> {
            if (v instanceof String s) payload.put(k, s);
            else if (v instanceof Integer i) payload.put(k, i);
            else if (v instanceof Long l) payload.put(k, l);
            else if (v instanceof Boolean b) payload.put(k, b);
            else payload.put(k, String.valueOf(v));
        });
        orchestrator.appendEvent(runId, seq.getAndIncrement(), type, payload);
    }

    private String jsonContent(String content) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("content", content);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"content\":\"" + content.replace("\"", "\\\"") + "\"}";
        }
    }
}
```

- [ ] **Step 5: 运行测试**

```bash
mvn -Dtest=ReactAgentServiceTest test 2>&1 | grep -E "BUILD|Tests run"
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/mingming/agent/react/ \
        backend/src/test/java/com/mingming/agent/react/ReactAgentServiceTest.java
git commit -m "feat: 新增 ReactAgentService（ReAct 显式 loop）"
```

---

## Task 10：AgentOrchestrator 清理 + ChatController 接入

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
- Modify: `backend/src/main/java/com/mingming/agent/controller/ChatController.java`
- Modify: `backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java`

- [ ] **Step 1: 清理 AgentOrchestrator**

读取 `AgentOrchestrator.java`（回退后已是干净基线，无 Codex 遗留方法）。

确认以下方法**存在且保留**（供 ReactAgentService 调用）：
- `public RunInit startRun(...)`
- `public void appendEvent(UUID runId, int seq, RunEventType type, ObjectNode payload)`
- `public void appendEvent(UUID runId, int seq, String type, ObjectNode payload)`
- `public List<Message> buildPromptMessages(UUID sessionId, String userText)`
- `public void runOnce(UUID runId, UUID sessionId, String userText, Consumer<String> sseDataConsumer)`

若回退后仍有 `executeSingleTurn` 方法（说明 revert 不完整），手动删除它及其调用的私有方法：`executeTurn`、`executeLoop`、`inspectTurnToolOutcome`、`buildLoopTerminationFallback`、`normalizeAssistantContent`（注意：`runOnce` 里的 `normalizeAssistantContent` 也调用了它，一起删除并在 `runOnce` 里直接用原始内容）。

- [ ] **Step 2: 修改 ChatController，接入 ReactAgentService**

将 `orchestrator.executeSingleTurn(...)` 替换为：

```java
// ChatController 中注入 ReactAgentService
private final ReactAgentService reactAgentService;

// 在线程体内替换调用：
reactAgentService.execute(
        runId,
        init.sessionId(),
        req.message(),
        TerminationPolicy.defaults(),
        data -> {
            try {
                emitter.send(SseEmitter.event().name("event").data(data));
            } catch (IOException e) {
                // client disconnected
            }
        });
```

- [ ] **Step 3: 运行全量后端测试**

```bash
cd backend
export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH"
mvn test 2>&1 | tail -20
```
预期：BUILD SUCCESS

- [ ] **Step 4: 手工验证简单对话**

启动后端（需要 DB + API key），发送：
```bash
curl -X POST http://localhost:18080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer dev-token-change-me" \
  -d '{"message":"你好"}' \
  -N
```
预期：一轮输出，无 "问题过于复杂" 错误，无多轮循环

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java \
        backend/src/main/java/com/mingming/agent/controller/ChatController.java \
        backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java
git commit -m "feat: AgentOrchestrator 清理，ChatController 接入 ReactAgentService"
```

---

## Task 11：注册 AgentTool Bean

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/react/AgentToolConfig.java`

- [ ] **Step 1: 创建 Spring 配置，注册工具 Bean**

```java
package com.mingming.agent.react;

import com.mingming.agent.react.tool.AgentTool;
import com.mingming.agent.react.tool.FetchTool;
import com.mingming.agent.react.tool.FileTool;
import com.mingming.agent.react.tool.ShellTool;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentToolConfig {

    @Bean
    public List<AgentTool> agentTools() {
        return List.of(new FetchTool(), new FileTool(), new ShellTool());
    }
}
```

- [ ] **Step 2: 确认 ToolDispatcher 能通过 List<AgentTool> 注入**

`ToolDispatcher` 已声明构造函数接受 `List<AgentTool>`，Spring 会自动注入 `agentTools()` Bean。

- [ ] **Step 3: 运行后端测试**

```bash
mvn test 2>&1 | grep -E "BUILD|FAIL"
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/mingming/agent/react/AgentToolConfig.java
git commit -m "feat: 注册 AgentTool Bean（FetchTool/FileTool/ShellTool）"
```

---

## Task 12：前端类型 + API

**Files:**
- Modify: `frontend/src/types/run.ts`
- Modify: `frontend/src/services/api.ts`

- [ ] **Step 1: 在 run.ts 中新增 ToolConfirmPayload 类型**

在 `frontend/src/types/run.ts` 末尾追加：

```typescript
export interface ToolConfirmPayload {
  type: 'TOOL_CONFIRM_REQUIRED'
  toolCallId: string
  toolName: string
  args: Record<string, unknown>
  reason: string
}

export interface PendingToolConfirm {
  toolCallId: string
  toolName: string
  args: Record<string, unknown>
  reason: string
  runId: string
}
```

- [ ] **Step 2: 在 api.ts 中新增 postToolConfirm**

在 `frontend/src/services/api.ts` 中添加：

```typescript
export async function postToolConfirm(
  runId: string,
  toolCallId: string,
  approved: boolean,
): Promise<void> {
  const response = await fetch(`/api/runs/${runId}/tool-confirm`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${DEV_TOKEN}`,
    },
    body: JSON.stringify({ toolCallId, approved }),
  })
  if (!response.ok) {
    throw new Error(`工具确认请求失败：${response.status}`)
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/types/run.ts frontend/src/services/api.ts
git commit -m "feat: 前端新增 ToolConfirm 类型和 API"
```

---

## Task 13：前端 eventMapper 扩展

**Files:**
- Modify: `frontend/src/services/eventMapper.ts`
- Test: `frontend/src/services/eventMapper.test.ts`

- [ ] **Step 1: 在 eventMapper.ts 新增三个摘要函数，并注册到 summarizePayload**

在 `summarizePayload` 函数开头的事件判断中追加：

```typescript
if (eventType === 'TOOL_CONFIRM_REQUIRED') {
  const p = payload as { toolName?: unknown; reason?: unknown }
  const toolName = typeof p.toolName === 'string' ? p.toolName : '未知工具'
  const reason = typeof p.reason === 'string' ? p.reason : ''
  return `⏳ 等待确认：${toolName}${reason ? ' | ' + reason : ''}`
}

if (eventType === 'TOOL_CONFIRM_RESPONSE') {
  const p = payload as { approved?: unknown; toolCallId?: unknown }
  const approved = p.approved === true
  return approved ? '✅ 用户已允许执行' : '⏭️ 用户跳过执行'
}

if (eventType === 'MODEL_OUTPUT') {
  const p = payload as { turnIndex?: unknown; content?: unknown }
  const turn = typeof p.turnIndex === 'number' ? p.turnIndex : '?'
  const content = typeof p.content === 'string' ? p.content.substring(0, 80) : ''
  return `🧠 第 ${turn} 轮推理${content ? '：' + content : ''}`
}

if (eventType === 'RUN_COMPLETED') {
  const p = payload as { totalTurns?: unknown; totalDurationMs?: unknown }
  const turns = typeof p.totalTurns === 'number' ? p.totalTurns : '?'
  const ms = typeof p.totalDurationMs === 'number' ? p.totalDurationMs : null
  return `✅ 运行完成 | 共 ${turns} 轮${ms !== null ? ' | 耗时 ' + ms + 'ms' : ''}`
}

if (eventType === 'RUN_TERMINATED') {
  const p = payload as { reason?: unknown; totalTurns?: unknown }
  const reason = typeof p.reason === 'string' ? p.reason : 'UNKNOWN'
  const turns = typeof p.totalTurns === 'number' ? p.totalTurns : '?'
  const reasonMap: Record<string, string> = {
    MAX_TURNS: '达到最大轮次',
    TIMEOUT: '执行超时',
    CONSECUTIVE_ERRORS: '连续工具失败',
  }
  return `⚠️ 运行终止 | ${reasonMap[reason] ?? reason} | 共 ${turns} 轮`
}
```

- [ ] **Step 2: 在 eventMapper.test.ts 追加测试**

```typescript
it('summarizes TOOL_CONFIRM_REQUIRED', () => {
  const result = summarizePayload(
    { toolName: 'shell_exec', reason: '即将执行命令：npm install' },
    'TOOL_CONFIRM_REQUIRED',
  )
  expect(result).toContain('shell_exec')
  expect(result).toContain('npm install')
})

it('summarizes RUN_COMPLETED', () => {
  const result = summarizePayload(
    { totalTurns: 3, totalDurationMs: 4200 },
    'RUN_COMPLETED',
  )
  expect(result).toContain('3')
  expect(result).toContain('4200')
})

it('summarizes RUN_TERMINATED MAX_TURNS', () => {
  const result = summarizePayload({ reason: 'MAX_TURNS', totalTurns: 10 }, 'RUN_TERMINATED')
  expect(result).toContain('最大轮次')
})
```

- [ ] **Step 3: 运行前端测试**

```bash
cd frontend
npm run test:unit 2>&1 | tail -20
```
预期：all tests pass

- [ ] **Step 4: Commit**

```bash
git add frontend/src/services/eventMapper.ts frontend/src/services/eventMapper.test.ts
git commit -m "feat: eventMapper 新增 ReAct 事件摘要映射"
```

---

## Task 14：ToolConfirmCard 组件 + TimelinePanel 接入

**Files:**
- Create: `frontend/src/components/ToolConfirmCard.vue`
- Modify: `frontend/src/components/TimelinePanel.vue`

- [ ] **Step 1: 创建 ToolConfirmCard.vue**

```vue
<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  toolCallId: string
  toolName: string
  reason: string
  args: Record<string, unknown>
  runId: string
  onConfirm: (toolCallId: string, approved: boolean) => void
}>()

const pending = ref(true)
const decision = ref<'approved' | 'skipped' | null>(null)

function handleApprove() {
  pending.value = false
  decision.value = 'approved'
  props.onConfirm(props.toolCallId, true)
}

function handleSkip() {
  pending.value = false
  decision.value = 'skipped'
  props.onConfirm(props.toolCallId, false)
}
</script>

<template>
  <div class="tool-confirm-card" :class="{ resolved: !pending }">
    <div class="card-header">
      <span class="icon">🔧</span>
      <span class="tool-name">{{ toolName }}</span>
      <span v-if="!pending" class="badge" :class="decision">
        {{ decision === 'approved' ? '已允许' : '已跳过' }}
      </span>
    </div>
    <p class="reason">{{ reason }}</p>
    <details class="args-detail">
      <summary>查看参数</summary>
      <pre>{{ JSON.stringify(args, null, 2) }}</pre>
    </details>
    <div v-if="pending" class="actions">
      <button class="btn-approve" @click="handleApprove">允许执行</button>
      <button class="btn-skip" @click="handleSkip">跳过</button>
    </div>
  </div>
</template>

<style scoped>
.tool-confirm-card {
  border: 1px solid #f59e0b;
  border-radius: 8px;
  padding: 12px 16px;
  margin: 8px 0;
  background: #fffbeb;
}
.tool-confirm-card.resolved {
  border-color: #d1d5db;
  background: #f9fafb;
  opacity: 0.8;
}
.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  margin-bottom: 8px;
}
.tool-name { font-family: monospace; }
.badge {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 12px;
  font-weight: normal;
}
.badge.approved { background: #d1fae5; color: #065f46; }
.badge.skipped { background: #f3f4f6; color: #6b7280; }
.reason { margin: 0 0 8px; color: #374151; font-size: 14px; }
.args-detail summary { cursor: pointer; font-size: 13px; color: #6b7280; }
.args-detail pre {
  background: #f3f4f6;
  padding: 8px;
  border-radius: 4px;
  font-size: 12px;
  overflow-x: auto;
  margin-top: 4px;
}
.actions { display: flex; gap: 8px; margin-top: 10px; }
.btn-approve {
  background: #10b981;
  color: white;
  border: none;
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
  font-size: 14px;
}
.btn-approve:hover { background: #059669; }
.btn-skip {
  background: white;
  color: #374151;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 6px 16px;
  cursor: pointer;
  font-size: 14px;
}
.btn-skip:hover { background: #f9fafb; }
</style>
```

- [ ] **Step 2: 在 TimelinePanel.vue 中导入并渲染确认卡片**

在 `<script setup>` 中添加 import：
```typescript
import ToolConfirmCard from './ToolConfirmCard.vue'

const emit = defineEmits<{
  toolConfirm: [toolCallId: string, approved: boolean]
}>()
```

在时间线条目模板中，对 `TOOL_CONFIRM_REQUIRED` 类型追加 ToolConfirmCard 渲染：
```vue
<template v-for="item in items" :key="item.id">
  <!-- 在现有条目渲染后，追加确认卡片 -->
  <ToolConfirmCard
    v-if="item.type === 'TOOL_CONFIRM_REQUIRED'"
    :tool-call-id="parseToolCallId(item.rawPayload)"
    :tool-name="parseToolName(item.rawPayload)"
    :reason="parseProp(item.rawPayload, 'reason')"
    :args="parseArgs(item.rawPayload)"
    :run-id="props.runId"
    :on-confirm="(id, approved) => emit('toolConfirm', id, approved)"
  />
</template>
```

在 `<script setup>` 中添加辅助函数：
```typescript
function parseToolCallId(rawPayload: string): string {
  try { return (JSON.parse(rawPayload) as { toolCallId?: string }).toolCallId ?? '' }
  catch { return '' }
}
function parseToolName(rawPayload: string): string {
  try { return (JSON.parse(rawPayload) as { toolName?: string }).toolName ?? '未知工具' }
  catch { return '未知工具' }
}
function parseProp(rawPayload: string, key: string): string {
  try { return String((JSON.parse(rawPayload) as Record<string, unknown>)[key] ?? '') }
  catch { return '' }
}
function parseArgs(rawPayload: string): Record<string, unknown> {
  try { return (JSON.parse(rawPayload) as { args?: Record<string, unknown> }).args ?? {} }
  catch { return {} }
}
```

- [ ] **Step 3: 在 App.vue/useChatConsole.ts 中处理 toolConfirm 事件，调用 postToolConfirm**

在 `useChatConsole.ts` 中添加：
```typescript
import { postToolConfirm } from '../services/api'

async function handleToolConfirm(runId: string, toolCallId: string, approved: boolean) {
  try {
    await postToolConfirm(runId, toolCallId, approved)
  } catch (e) {
    console.error('工具确认失败', e)
  }
}
```

在 `App.vue` 中将 `@toolConfirm` 事件绑定到 `handleToolConfirm`。

- [ ] **Step 4: 运行前端测试 + 构建**

```bash
cd frontend
npm run test:unit 2>&1 | tail -10
npm run build 2>&1 | tail -10
```
预期：无报错

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/ToolConfirmCard.vue \
        frontend/src/components/TimelinePanel.vue \
        frontend/src/composables/useChatConsole.ts \
        frontend/src/App.vue
git commit -m "feat: 新增 ToolConfirmCard 组件，接入工具确认交互"
```

---

## Task 15：全链路验收

- [ ] **Step 1: 运行全量后端测试**

```bash
cd backend
export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH"
mvn test 2>&1 | grep -E "Tests run|BUILD|FAIL"
```
预期：BUILD SUCCESS，0 failures

- [ ] **Step 2: 运行前端测试 + 构建**

```bash
cd frontend
npm run test:unit 2>&1 | tail -5
npm run build 2>&1 | tail -5
```

- [ ] **Step 3: 手工验证场景 1 — 简单问候**

发送"你好"，预期：
- 1 轮结束，无工具调用
- SSE 包含自然语言回答
- 时间线：USER_MESSAGE → MODEL_OUTPUT → RUN_COMPLETED

- [ ] **Step 4: 手工验证场景 2 — GitHub 链接**

发送一个 GitHub README 链接，预期：
- agent 自动调用 fetch_page（白名单，无需确认）
- 时间线：TOOL_CALL → TOOL_RESULT → MODEL_OUTPUT（分析内容）

- [ ] **Step 5: 手工验证场景 3 — 需确认操作**

发送"帮我创建一个 test.txt 文件写入 hello"，预期：
- 前端出现 ToolConfirmCard（黄色边框）
- 点击"允许"后文件被创建
- 时间线：TOOL_CONFIRM_REQUIRED → TOOL_CALL → TOOL_RESULT

- [ ] **Step 6: 最终合并提交**

```bash
git log --oneline master..HEAD
# 确认所有提交整洁后，可选 squash merge
git commit --allow-empty -m "feat: ReAct Agent Loop 全链路落地"
```
