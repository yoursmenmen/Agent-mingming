# Structured Schema Chat Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在聊天主消息区落地统一 `structured v1` 协议与 `weather/calc_result/tool_error` 三类高质感结构化卡片，同时保持流式链路、事件持久化与回放稳定。

**Architecture:** 后端新增结构化 payload 组装器，从 `TOOL_RESULT` 事件提取并标准化输出 `type/version/data/meta`；`AgentOrchestrator` 仅负责挂载该结构化数据到最终 `MODEL_MESSAGE`。前端新增结构化类型守卫与卡片渲染注册表，在 `ChatPanel` 的 assistant 消息内按类型渲染卡片并保留文本回退，样式统一走樱花粉轻拟物玻璃主题 token。

**Tech Stack:** Spring Boot 3.3, Spring AI Alibaba, JUnit 5 + Mockito, Vue 3 + TypeScript + Vite, Vitest + Vue Test Utils, CSS Design Tokens。

---

## 文件结构与职责

- `backend/src/main/java/com/mingming/agent/orchestrator/StructuredPayloadAssembler.java`
  - 新增：统一组装 `structured v1`，支持 `weather/calc_result/tool_error`。
- `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
  - 修改：使用组装器，移除仅天气的硬编码提取逻辑。
- `backend/src/test/java/com/mingming/agent/orchestrator/StructuredPayloadAssemblerTest.java`
  - 新增：组装器单元测试（3 类型 + 容错）。
- `backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java`
  - 修改：更新结构化字段断言与新增回归断言。
- `frontend/package.json`
  - 修改：新增 `test:unit` 脚本与测试依赖。
- `frontend/vite.config.ts`
  - 修改：补充 Vitest 配置（`jsdom` + include）。
- `frontend/src/types/structured.ts`
  - 新增：结构化协议类型定义。
- `frontend/src/services/structured.ts`
  - 新增：结构化数据解析、类型守卫、回退策略。
- `frontend/src/services/structured.test.ts`
  - 新增：解析器单测。
- `frontend/src/components/structured/StructuredCardHost.vue`
  - 新增：卡片注册与路由。
- `frontend/src/components/structured/StructuredCardShell.vue`
  - 新增：统一玻璃外壳。
- `frontend/src/components/structured/WeatherCard.vue`
  - 新增：天气卡片。
- `frontend/src/components/structured/CalcResultCard.vue`
  - 新增：计算结果卡片。
- `frontend/src/components/structured/ToolErrorCard.vue`
  - 新增：工具异常卡片。
- `frontend/src/components/structured/UnknownStructuredCard.vue`
  - 新增：未知类型兜底卡片。
- `frontend/src/components/ChatPanel.vue`
  - 修改：assistant 消息中挂载结构化卡片。
- `frontend/src/types/chat.ts`
  - 修改：`ChatMessage` 增加可选 `structured` 字段。
- `frontend/src/composables/useChatConsole.ts`
  - 修改：在流式结束后从历史 `MODEL_MESSAGE` payload 回填结构化数据到当前 assistant 消息。
- `frontend/src/components/structured/StructuredCardHost.test.ts`
  - 新增：卡片映射与兜底渲染测试。
- `frontend/src/style.css`
  - 修改：新增樱花粉玻璃卡片 token 与响应式样式。
- `docs/changes-log.md`
  - 修改：记录本次协议与 UI 变更。

### Task 1: 后端统一结构化组装器

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/orchestrator/StructuredPayloadAssembler.java`
- Create: `backend/src/test/java/com/mingming/agent/orchestrator/StructuredPayloadAssemblerTest.java`
- Test: `backend/src/test/java/com/mingming/agent/orchestrator/StructuredPayloadAssemblerTest.java`

- [ ] **Step 1: 先写失败测试（3 类型 + 容错）**

```java
package com.mingming.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mingming.agent.entity.RunEventEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredPayloadAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void assemble_shouldBuildWeatherStructuredV1() {
        StructuredPayloadAssembler assembler = new StructuredPayloadAssembler(objectMapper);
        RunEventEntity weather = new RunEventEntity();
        weather.setType("TOOL_RESULT");
        weather.setPayload("""
                {"tool":"get_weather","data":{"ok":true,"city":"北京","weather":"晴","temperature":"26","humidity":"42","windDirection":"东南","windPower":"3","reportTime":"2026-03-30 09:00:00"}}
                """);

        var result = assembler.assemble(List.of(weather));

        assertThat(result).isPresent();
        assertThat(result.get().path("type").asText()).isEqualTo("weather");
        assertThat(result.get().path("version").asText()).isEqualTo("v1");
        assertThat(result.get().path("data").path("city").asText()).isEqualTo("北京");
    }

    @Test
    void assemble_shouldBuildCalcResultStructuredV1() {
        StructuredPayloadAssembler assembler = new StructuredPayloadAssembler(objectMapper);
        RunEventEntity calc = new RunEventEntity();
        calc.setType("TOOL_RESULT");
        calc.setPayload("""
                {"tool":"add","data":{"result":22.0},"args":{"a":12.0,"b":10.0}}
                """);

        var result = assembler.assemble(List.of(calc));

        assertThat(result).isPresent();
        assertThat(result.get().path("type").asText()).isEqualTo("calc_result");
        assertThat(result.get().path("data").path("result").asDouble()).isEqualTo(22.0);
    }

    @Test
    void assemble_shouldBuildToolErrorWhenToolReturnedFailure() {
        StructuredPayloadAssembler assembler = new StructuredPayloadAssembler(objectMapper);
        RunEventEntity failure = new RunEventEntity();
        failure.setType("TOOL_RESULT");
        failure.setPayload("""
                {"tool":"get_weather","data":{"ok":false,"error":"weather request failed: timeout"}}
                """);

        var result = assembler.assemble(List.of(failure));

        assertThat(result).isPresent();
        assertThat(result.get().path("type").asText()).isEqualTo("tool_error");
        assertThat(result.get().path("data").path("retryable").asBoolean()).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=StructuredPayloadAssemblerTest test`
Expected: FAIL，提示 `StructuredPayloadAssembler` 不存在或方法未实现。

- [ ] **Step 3: 写最小实现让测试通过**

```java
package com.mingming.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mingming.agent.entity.RunEventEntity;
import com.mingming.agent.event.RunEventType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StructuredPayloadAssembler {

    private final ObjectMapper objectMapper;

    public Optional<ObjectNode> assemble(List<RunEventEntity> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            RunEventEntity event = events.get(i);
            if (!RunEventType.TOOL_RESULT.name().equals(event.getType())) {
                continue;
            }
            Optional<ObjectNode> node = buildFromToolResult(event.getPayload());
            if (node.isPresent()) {
                return node;
            }
        }
        return Optional.empty();
    }

    private Optional<ObjectNode> buildFromToolResult(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            String tool = root.path("tool").asText("");
            JsonNode data = root.path("data");

            if ("get_weather".equals(tool) && data.path("ok").asBoolean(false)) {
                ObjectNode structured = objectMapper.createObjectNode();
                structured.put("type", "weather");
                structured.put("version", "v1");
                ObjectNode dataNode = structured.putObject("data");
                dataNode.put("city", data.path("city").asText(""));
                dataNode.put("condition", data.path("weather").asText(""));
                dataNode.put("tempC", safeDouble(data.path("temperature")));
                dataNode.put("humidity", safeDouble(data.path("humidity")));
                dataNode.put("windKph", safeDouble(data.path("windPower")));
                ObjectNode meta = structured.putObject("meta");
                meta.put("toolName", "get_weather");
                meta.put("source", "amap");
                meta.put("generatedAt", OffsetDateTime.now().toString());
                return Optional.of(structured);
            }

            if ("add".equals(tool) && data.has("result")) {
                ObjectNode structured = objectMapper.createObjectNode();
                structured.put("type", "calc_result");
                structured.put("version", "v1");
                ObjectNode dataNode = structured.putObject("data");
                dataNode.put("expression", "a + b");
                dataNode.put("result", data.path("result").asDouble());
                dataNode.putNull("unit");
                ObjectNode meta = structured.putObject("meta");
                meta.put("toolName", "add");
                meta.put("generatedAt", OffsetDateTime.now().toString());
                return Optional.of(structured);
            }

            if (data.has("ok") && !data.path("ok").asBoolean(true)) {
                ObjectNode structured = objectMapper.createObjectNode();
                structured.put("type", "tool_error");
                structured.put("version", "v1");
                ObjectNode dataNode = structured.putObject("data");
                dataNode.put("toolName", tool);
                String message = data.path("error").asText("tool failed");
                dataNode.put("category", message.toLowerCase().contains("timeout") ? "UPSTREAM_TIMEOUT" : "UPSTREAM_ERROR");
                dataNode.put("message", message);
                dataNode.put("retryable", message.toLowerCase().contains("timeout"));
                structured.putObject("meta").put("generatedAt", OffsetDateTime.now().toString());
                return Optional.of(structured);
            }

            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private double safeDouble(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return Double.NaN;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        try {
            return Double.parseDouble(value.asText(""));
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=StructuredPayloadAssemblerTest test`
Expected: PASS，3 个测试全部通过。

- [ ] **Step 5: 提交当前任务**

```bash
git add backend/src/main/java/com/mingming/agent/orchestrator/StructuredPayloadAssembler.java backend/src/test/java/com/mingming/agent/orchestrator/StructuredPayloadAssemblerTest.java
git commit -m "feat: add unified structured payload assembler for tool results"
```

### Task 2: 后端接入编排链路与回归测试

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`
- Modify: `backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java`
- Test: `backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java`

- [ ] **Step 1: 先写失败测试（新 schema 断言）**

```java
@Test
void buildFinalModelMessagePayload_shouldUseUnifiedEnvelope() {
    StructuredPayloadAssembler assembler = new StructuredPayloadAssembler(new ObjectMapper());
    AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModelProvider,
            new ObjectMapper(),
            chatSessionRepository,
            agentRunRepository,
            runEventRepository,
            List.<LocalToolProvider>of(),
            assembler);

    UUID runId = UUID.randomUUID();
    RunEventEntity weatherToolResult = new RunEventEntity();
    weatherToolResult.setType("TOOL_RESULT");
    weatherToolResult.setPayload("{" +
            "\"tool\":\"get_weather\"," +
            "\"data\":{\"ok\":true,\"city\":\"北京\",\"weather\":\"晴\",\"temperature\":\"26\",\"humidity\":\"42\",\"windPower\":\"3\"}}"
    );
    when(runEventRepository.findByRunIdOrderBySeqAsc(runId)).thenReturn(List.of(weatherToolResult));

    ObjectNode payload = orchestrator.buildFinalModelMessagePayload(runId, "天气结果");

    assertThat(payload.path("structured").path("type").asText()).isEqualTo("weather");
    assertThat(payload.path("structured").path("version").asText()).isEqualTo("v1");
    assertThat(payload.path("structured").path("data").path("city").asText()).isEqualTo("北京");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=AgentOrchestratorTest test`
Expected: FAIL，构造函数参数不匹配或 `structured.type/version` 断言失败。

- [ ] **Step 3: 最小改造接入组装器**

```java
// AgentOrchestrator 字段新增
private final StructuredPayloadAssembler structuredPayloadAssembler;

ObjectNode buildFinalModelMessagePayload(UUID runId, String content) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("content", content == null ? "" : content);

    List<RunEventEntity> events = runEventRepository.findByRunIdOrderBySeqAsc(runId);
    structuredPayloadAssembler.assemble(events).ifPresent(structured -> payload.set("structured", structured));
    return payload;
}
```

- [ ] **Step 4: 运行回归测试确认通过**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn -Dtest=AgentOrchestratorTest,StructuredPayloadAssemblerTest test`
Expected: PASS，编排测试与组装器测试均通过。

- [ ] **Step 5: 提交当前任务**

```bash
git add backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java
git commit -m "refactor: wire structured payload assembler into orchestrator"
```

### Task 3: 前端结构化协议解析与测试基建

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/tsconfig.app.json`
- Create: `frontend/src/types/structured.ts`
- Create: `frontend/src/services/structured.ts`
- Create: `frontend/src/services/structured.test.ts`
- Test: `frontend/src/services/structured.test.ts`

- [ ] **Step 1: 先写失败测试（解析器）**

```ts
import { describe, expect, it } from 'vitest'
import { parseStructuredPayload } from './structured'

describe('parseStructuredPayload', () => {
  it('should accept weather v1 payload', () => {
    const result = parseStructuredPayload({
      type: 'weather',
      version: 'v1',
      data: { city: '北京', condition: '晴', tempC: 26 },
    })
    expect(result?.type).toBe('weather')
  })

  it('should fallback to unknown when type is unsupported', () => {
    const result = parseStructuredPayload({
      type: 'future_type',
      version: 'v1',
      data: { foo: 'bar' },
    })
    expect(result?.type).toBe('unknown')
  })

  it('should return null when envelope is invalid', () => {
    const result = parseStructuredPayload({
      version: 'v1',
      data: {},
    })
    expect(result).toBeNull()
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && npm run test:unit -- src/services/structured.test.ts`
Expected: FAIL，提示 `test:unit` 脚本不存在或 `parseStructuredPayload` 未定义。

- [ ] **Step 3: 添加测试配置与解析器最小实现**

```json
// frontend/package.json (scripts/devDependencies 关键变更)
{
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "preview": "vite preview",
    "lint": "eslint . --ext .ts,.tsx,.js,.jsx,.vue",
    "test:unit": "vitest run"
  },
  "devDependencies": {
    "vitest": "^2.1.8",
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^25.0.1"
  }
}
```

```ts
// frontend/vite.config.ts (完整建议内容)
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    globals: true,
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/health': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
    },
  },
})
```

```json
// frontend/tsconfig.app.json (compilerOptions 关键增量)
{
  "compilerOptions": {
    "types": ["vite/client", "vitest/globals"]
  },
  "include": ["src/**/*.ts", "src/**/*.tsx", "src/**/*.vue"]
}
```

```ts
// frontend/src/services/structured.ts
import type { StructuredEnvelope, StructuredPayload } from '../types/structured'

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export function parseStructuredPayload(input: unknown): StructuredPayload | null {
  if (!isObject(input)) return null
  const type = input.type
  const version = input.version
  const data = input.data
  const meta = isObject(input.meta) ? input.meta : {}

  if (typeof type !== 'string' || typeof version !== 'string' || !isObject(data)) {
    return null
  }
  if (version !== 'v1') {
    return null
  }

  if (type === 'weather' || type === 'calc_result' || type === 'tool_error') {
    return { type, version, data, meta } as StructuredEnvelope
  }

  return { type: 'unknown', version: 'v1', data, meta }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd frontend && npm install && npm run test:unit -- src/services/structured.test.ts`
Expected: PASS，`parseStructuredPayload` 用例全部通过。

- [ ] **Step 5: 提交当前任务**

```bash
git add frontend/package.json frontend/vite.config.ts frontend/tsconfig.app.json frontend/src/types/structured.ts frontend/src/services/structured.ts frontend/src/services/structured.test.ts
git commit -m "test: add structured payload parser with vitest setup"
```

### Task 4: 聊天区结构化卡片组件与渲染接入

**Files:**
- Create: `frontend/src/components/structured/StructuredCardShell.vue`
- Create: `frontend/src/components/structured/WeatherCard.vue`
- Create: `frontend/src/components/structured/CalcResultCard.vue`
- Create: `frontend/src/components/structured/ToolErrorCard.vue`
- Create: `frontend/src/components/structured/UnknownStructuredCard.vue`
- Create: `frontend/src/components/structured/StructuredCardHost.vue`
- Create: `frontend/src/components/structured/StructuredCardHost.test.ts`
- Modify: `frontend/src/types/chat.ts`
- Modify: `frontend/src/composables/useChatConsole.ts`
- Modify: `frontend/src/components/ChatPanel.vue`
- Test: `frontend/src/components/structured/StructuredCardHost.test.ts`

- [ ] **Step 1: 先写失败测试（卡片映射 + 兜底）**

```ts
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StructuredCardHost from './StructuredCardHost.vue'

describe('StructuredCardHost', () => {
  it('renders weather card for weather payload', () => {
    const wrapper = mount(StructuredCardHost, {
      props: {
        structured: {
          type: 'weather',
          version: 'v1',
          data: { city: '北京', condition: '晴', tempC: 26 },
          meta: {},
        },
      },
    })
    expect(wrapper.find('[data-card="weather"]').exists()).toBe(true)
  })

  it('renders unknown card for unknown payload', () => {
    const wrapper = mount(StructuredCardHost, {
      props: {
        structured: {
          type: 'unknown',
          version: 'v1',
          data: { foo: 'bar' },
          meta: {},
        },
      },
    })
    expect(wrapper.find('[data-card="unknown"]').exists()).toBe(true)
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && npm run test:unit -- src/components/structured/StructuredCardHost.test.ts`
Expected: FAIL，组件文件不存在或选择器断言失败。

- [ ] **Step 3: 实现卡片组件并接入 ChatPanel**

```vue
<!-- frontend/src/components/structured/StructuredCardHost.vue -->
<script setup lang="ts">
import { computed } from 'vue'
import type { StructuredPayload } from '../../types/structured'
import WeatherCard from './WeatherCard.vue'
import CalcResultCard from './CalcResultCard.vue'
import ToolErrorCard from './ToolErrorCard.vue'
import UnknownStructuredCard from './UnknownStructuredCard.vue'

const props = defineProps<{ structured: StructuredPayload | null }>()

const resolved = computed(() => {
  if (!props.structured) return UnknownStructuredCard
  if (props.structured.type === 'weather') return WeatherCard
  if (props.structured.type === 'calc_result') return CalcResultCard
  if (props.structured.type === 'tool_error') return ToolErrorCard
  return UnknownStructuredCard
})
</script>

<template>
  <component :is="resolved" :structured="structured" />
</template>
```

```ts
// frontend/src/types/chat.ts (关键增量)
import type { StructuredPayload } from './structured'

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  createdAt: string
  status?: 'streaming' | 'done' | 'error'
  structured?: StructuredPayload | null
}
```

```vue
<!-- frontend/src/components/ChatPanel.vue (模板关键增量) -->
<StructuredCardHost
  v-if="message.role === 'assistant' && message.structured"
  :structured="message.structured"
/>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd frontend && npm run test:unit -- src/components/structured/StructuredCardHost.test.ts src/services/structured.test.ts`
Expected: PASS，卡片路由与解析器测试通过。

- [ ] **Step 5: 提交当前任务**

```bash
git add frontend/src/components/structured frontend/src/types/chat.ts frontend/src/composables/useChatConsole.ts frontend/src/components/ChatPanel.vue
git commit -m "feat: render structured cards in assistant chat messages"
```

### Task 5: 樱花粉轻拟物玻璃主题细化、构建与回归

**Files:**
- Modify: `frontend/src/style.css`
- Modify: `docs/changes-log.md`
- Create: `frontend/src/components/structured/style-smoke.test.ts`
- Test: `frontend/src/components/structured/StructuredCardHost.test.ts`

- [ ] **Step 1: 先写失败样式/可访问性检查用例（最小 smoke）**

```ts
import { describe, expect, it } from 'vitest'
import fs from 'node:fs'

describe('structured card style tokens', () => {
  it('contains sakura glass tokens', () => {
    const css = fs.readFileSync('src/style.css', 'utf8')
    expect(css.includes('--sakura-50')).toBe(true)
    expect(css.includes('.structured-card')).toBe(true)
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && npm run test:unit -- src/components/structured/style-smoke.test.ts`
Expected: FAIL，token 或 class 尚未定义。

- [ ] **Step 3: 实现樱花粉玻璃卡片样式并更新变更日志**

```css
/* frontend/src/style.css 关键增量 */
:root {
  --sakura-50: #fff7fb;
  --sakura-100: #ffeef7;
  --sakura-200: #ffd6ea;
  --sakura-400: #ea8db7;
  --sakura-600: #c95789;
  --line-soft: rgba(227, 139, 180, 0.28);
}

.structured-card {
  margin-top: 10px;
  border-radius: 16px;
  border: 1px solid var(--line-soft);
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.7), rgba(255, 245, 250, 0.52));
  backdrop-filter: blur(10px);
  box-shadow:
    0 12px 28px rgba(208, 113, 156, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.65);
  animation: structured-card-in 200ms ease;
}

@keyframes structured-card-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 768px) {
  .weather-card-grid {
    grid-template-columns: 1fr;
  }
}
```

- [ ] **Step 4: 运行完整前端校验**

Run: `cd frontend && npm run test:unit && npm run build`
Expected: PASS，测试与构建通过。

- [ ] **Step 5: 运行后端回归 + 提交任务**

Run: `export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn test -Dtest=AgentOrchestratorTest,StructuredPayloadAssemblerTest,ToolEventServiceTest`
Expected: PASS，结构化新增不破坏工具事件与编排。

```bash
git add frontend/src/style.css docs/changes-log.md
git commit -m "style: apply sakura glass structured card system"
```

## 端到端验收脚本

- [ ] 启动后端：`export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn spring-boot:run`
- [ ] 启动前端：`cd frontend && npm run dev`
- [ ] 手工回归用例：
  - 输入天气问题，确认 assistant 文本 + `weather` 卡片同时出现。
  - 输入计算问题（如“12+10 是多少”），确认出现 `calc_result` 卡片。
  - 断开或模拟天气超时，确认出现 `tool_error` 卡片且文本链路不断。
  - 手机宽度下检查卡片不溢出、可读性正常。

## 计划自检

### 1) Spec 覆盖检查

- 统一 schema：Task 1, Task 2。
- 3 类卡片渲染：Task 4。
- 樱花粉轻拟物玻璃视觉：Task 5。
- 兼容与回退：Task 3, Task 4。
- 测试与构建：Task 1~5 + 端到端验收脚本。

### 2) 占位符扫描

- 已检查：无 `TODO`、`TBD`、`implement later` 等占位词。

### 3) 类型一致性检查

- `structured` 协议字段统一为 `type/version/data/meta`。
- 前后端类型名统一：`weather`、`calc_result`、`tool_error`、`unknown`。
