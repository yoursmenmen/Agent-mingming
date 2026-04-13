# 当前进度快照（2026-04-11）

本文件用于快速同步项目当前能力、已完成范围和待办问题，便于下次进入会话时快速对齐上下文。

## 1. 已完成能力

### 后端（ReAct + Chat）
- 聊天主链路已切换为显式 ReAct loop（`ReactAgentService`）：模型可在单次 run 内多轮调用工具。
- 已接入 AgentTool 三件套：`fetch_page`、`file_op`、`shell_exec`。
- 工具调度由 `ToolDispatcher` 统一处理，支持：
  - 工具名路由
  - 参数解析
  - 风险动作确认拦截
  - 错误兜底返回
- 工具确认接口可用：`POST /api/runs/{runId}/tool-confirm`。
- 运行终止策略可用：最大轮次、最大时长、连续错误阈值。
- 事件可观测：`USER_MESSAGE`、`MODEL_OUTPUT`、`TOOL_CALL`、`TOOL_RESULT`、`RUN_COMPLETED/RUN_TERMINATED` 持久化并可回放。

### 上下文管理（当前状态）
- 已有会话级上下文窗口保护（按消息数 + 字符数裁剪历史）。
- 已接入 Summary Memory（`SESSION_SUMMARY`），会在 run 结束时刷新会话摘要。
- prompt 组装已统一为：`system + summary + recent window + 当前问题`。

### 前端（控制台）
- 聊天区支持流式增量展示。
- 时间线可查看 run/session 历史事件。
- 工具调用与结果在时间线可读化展示。
- `MODEL_OUTPUT` 已按“会话轮次 + run 内推理次数”展示，避免多 run 下轮次歧义。
- 轮次折叠头支持 sticky，滚动时可直接折叠当前轮次。

## 2. 当前行为说明（重点）

- `POST /api/chat/stream` 返回 SSE：先发 `run`（`sessionId + runId`），再持续发 `event`。
- 当工具命中确认策略时，服务端先推送确认事件并阻塞等待用户确认（超时按拒绝处理）。
- 单次 run 对话上下文采用滑动窗口裁剪（保留 system/summary 前缀 + 最近窗口）。
- run 结束后会把关键上下文压缩为会话摘要并落库，供后续请求复用。

## 3. 已知问题与观察项

- 工具输出尚未统一做长度预算与结构化摘要，长文本工具结果会挤占模型上下文。
- `fetch_page` 对部分站点可能遭遇 403（目标站反爬策略），属于上游可用性问题。

## 4. 下一步建议

1. 增加工具输出预算（截断 + 摘要 + 关键字段保留），防止上下文被单次工具结果挤爆。
2. 增加 per-run 预算治理（token/工具次数/耗时）与中途终止原因可观测。
3. 优化摘要刷新策略（每次 run / 每 N 次 run）与摘要长度上限配置化。
4. 完善评测集与回归测试（成功率、平均轮次、工具失败恢复率、摘要质量）。
