# 统一 Schema + 聊天区结构化卡片设计

## 背景

当前项目已完成 MVP 工具调用主线，具备会话复用、run/event 持久化和 SSE 流式输出能力。下一阶段已批准目标是：统一结构化输出协议，并在前端聊天主消息区完成结构化渲染。

本设计聚焦于：

- 统一模型最终 payload 的结构化 schema。
- 中等覆盖卡片类型（`weather`、`calc_result`、`tool_error`）。
- 仅改造聊天主消息区（本阶段不改时间线和运行状态面板）。
- 视觉风格采用樱花粉主题 + 轻拟物玻璃感。

## 目标

1. 统一后端输出与前端解析的结构化协议。
2. 在 assistant 聊天消息中，以高质感卡片展示结构化信息。
3. 保证兼容性与稳定性：结构化数据缺失或异常时自动回退到现有文本渲染。
4. 保持当前 run/event 存储与回放行为不受影响。

## 非目标

- 本阶段不做时间线面板卡片化。
- 本阶段不做 run 状态面板重设计。
- 不调整显式自定义 while-loop 编排逻辑。
- 本阶段不引入 RAG。

## 架构设计

### 后端

- 保持现有编排策略：增量 delta 继续流式推送，持久化最终 `MODEL_MESSAGE` 与工具事件。
- 在最终模型 payload 中，按条件附加统一 `structured` 对象。
- 首批支持类型：
  - `weather`
  - `calc_result`
  - `tool_error`

### 前端

- 保持现有消息流与 SSE 解析逻辑。
- 扩展 assistant 消息渲染链路：
  1. 先按现状渲染文本。
  2. 检测 `structured` 字段。
  3. 若 `type` 已知且字段合法，渲染对应卡片并插入消息气泡内。
  4. 若未知或字段异常，渲染兜底卡片或保持纯文本。
- 变更范围严格限定在聊天主消息区。

## 统一 Schema（v1）

所有结构化输出统一使用如下外壳：

```json
{
  "type": "weather | calc_result | tool_error",
  "version": "v1",
  "data": {},
  "meta": {
    "toolName": "可选",
    "source": "可选",
    "generatedAt": "可选，ISO-8601 时间戳",
    "traceId": "可选"
  }
}
```

### 类型：`weather`（`v1`）

```json
{
  "type": "weather",
  "version": "v1",
  "data": {
    "city": "Hangzhou",
    "condition": "Cloudy",
    "tempC": 27,
    "feelsLikeC": 29,
    "humidity": 68,
    "windKph": 12
  },
  "meta": {
    "toolName": "get_weather",
    "source": "amap",
    "generatedAt": "2026-03-30T11:20:00+08:00"
  }
}
```

### 类型：`calc_result`（`v1`）

```json
{
  "type": "calc_result",
  "version": "v1",
  "data": {
    "expression": "12 + 30 / 3",
    "result": 22,
    "unit": null
  },
  "meta": {
    "toolName": "add",
    "generatedAt": "2026-03-30T11:21:00+08:00"
  }
}
```

### 类型：`tool_error`（`v1`）

```json
{
  "type": "tool_error",
  "version": "v1",
  "data": {
    "toolName": "get_weather",
    "category": "UPSTREAM_TIMEOUT",
    "message": "Weather service timeout, please retry.",
    "retryable": true
  },
  "meta": {
    "generatedAt": "2026-03-30T11:22:00+08:00"
  }
}
```

## 校验与回退规则

### 后端规则

- 至少包含 `type`、`version`、`data` 时才输出结构化对象。
- 结构化组装失败时不得中断主响应，继续返回正常文本消息。
- 结构化错误信息中不得记录 token、密钥等敏感内容。

### 前端规则

- 外壳必填字段缺失时，不渲染专用卡片。
- 未知 `type` 时渲染 `UnknownStructuredCard`，以安全 key-value 展示。
- 数值字段异常（例如 `tempC` 为字符串）时，卡片字段显示占位符 `--`，但不影响消息渲染。
- 文本渲染始终可用，作为最终兜底。

## 前端视觉系统

视觉方向：樱花粉主题 + 轻拟物玻璃感，强调信息层级和高级感。

### 设计 Token

- 色彩 token：
  - `--sakura-50`、`--sakura-100`、`--sakura-200`、`--sakura-400`、`--sakura-600`
  - `--ink-700`、`--ink-900`、`--line-soft`
- 表面 token：
  - 半透明卡片背景
  - 细微高光描边
  - 多层柔和阴影
- 布局 token：
  - 圆角 `16px`
  - 间距节奏 `4, 8, 12, 16, 24`

### 卡片组成

- `StructuredCardShell`
  - 统一外壳、头部、meta 信息区和入场动画。
- `WeatherCard`
  - 突出温度与天气状态，下方使用紧凑指标网格。
- `CalcResultCard`
  - 表达式区 + 强调结果区。
- `ToolErrorCard`
  - 温和告警视觉 + 可重试状态标签。
- `UnknownStructuredCard`
  - 面向未来扩展类型的兜底卡片。

### 动效与交互

- 卡片入场：轻微上浮 + 淡入（`160-220ms`）。
- Hover：仅轻描边高亮，不做明显位移。
- 键盘焦点：交互元素必须有可见 focus ring。

### 响应式行为

- 桌面端：信息密集区采用双列。
- 移动端：自动切换为单列布局。
- 长字符串（城市名、错误信息、表达式）必须换行，不能溢出容器。

## 数据流

1. 用户发送聊天请求。
2. 后端编排器执行模型/工具流程。
3. SSE 按现状推送增量 delta。
4. 后端输出最终模型消息，并可选附带 `structured` 外壳。
5. 前端按现状合并流式与最终消息。
6. 聊天渲染器检测 `structured` 并挂载对应卡片。
7. 对未知或异常结构化数据执行安全降级。

## 测试策略

### 后端

- 为以下类型补充结构化外壳生成单测：
  - `weather`
  - `calc_result`
  - `tool_error`
- 失败路径测试：工具输出异常时不影响主链路。
- 回归测试：run/event 持久化与回放接口行为保持不变。

### 前端

- 三类卡片的组件渲染测试。
- 未知类型与坏数据的兜底测试。
- 样式/token 冒烟测试，确保卡片外壳和主题类生效。

### 联调/人工验收

- 桌面端与移动端聊天区视觉验收。
- 流式场景验收：最终消息到达时无闪烁、无重复卡片。
- 长文本压力验收：换行与布局稳定。
- 可访问性验收：对比度可读、键盘焦点可见。

## 成功标准（DoD）

1. 聊天主消息区可渲染 `weather`、`calc_result`、`tool_error` 三类卡片。
2. 后端输出与前端解析统一使用 `v1` schema。
3. 结构化数据异常或未知时不影响聊天正常展示。
4. 樱花粉轻拟物玻璃感视觉生效且响应式正常。
5. 相关后端/前端测试与构建校验通过。

## 风险与缓解

- 风险：后端 schema 与前端解析漂移。
  - 缓解：集中维护类型定义与解析守卫函数。
- 风险：卡片样式好看但可读性下降。
  - 缓解：保证文本对比度和明确焦点状态。
- 风险：流式与最终消息合并时出现重复卡片。
  - 缓解：按 assistant 最终消息标识进行卡片挂载并复用现有合并语义。

## 本次迭代边界

- 包含：统一 schema 协议 + 3 类卡片 + 聊天区渲染 + 视觉 token 层。
- 不包含：时间线卡片化、RAG、高级工具治理。
