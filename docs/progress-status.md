# 当前进度快照（2026-04-02）

本文件用于快速同步项目当前能力、已完成范围和待办问题，便于下次进入会话时快速对齐上下文。

## 1. 已完成能力

### 后端（MCP + Chat）
- 聊天主链路已支持 **动态注入 MCP tools**（runtime callback），不再只依赖固定桥接函数。
- MCP 动态注入已支持治理参数：
  - `allow-tools`
  - `deny-tools`
  - `max-callbacks`
- MCP 工具参数已支持基础必填校验（读取 `inputSchema.required`）。
- 新增 `MCP_TOOLS_BOUND` run event，用于记录：注入清单、阻断清单、discovery 错误。
- `run_local_command` 已加入风险分级：
  - 硬拦截（如明显破坏性 `rm *` / `rm -rf /`）
  - 变更类命令进入 `PENDING_CONFIRMATION`
- 待确认动作接口已可用：
  - `GET /api/mcp/actions/pending`
  - `POST /api/mcp/actions/{actionId}/confirm`
  - `POST /api/mcp/actions/{actionId}/reject`
- confirm 失败已业务化返回（不再直接抛 500）。

### 本地 MCP 服务（Python）
- `tools/mcp/local_ops_mcp.py` 支持 `fetch_page` / `run_local_command` / `k8s_cluster_status`。
- Windows 环境下对 `npm/pnpm/yarn/npx/node` 增加 `.cmd` 兜底，减少 `[WinError 2]`。

### 前端（控制台）
- 时间线改为“关键事件实时可见”，并过滤 `MODEL_DELTA` 噪音。
- `TOOL_CALL` / `TOOL_RESULT` 摘要可读性已增强（包含 tool、关键参数与状态）。
- 待确认命令操作入口已放到聊天区（确认/拒绝），不再放时间线中。
- confirm 后会自动发起一次 follow-up 对话，把执行结果喂给模型，让模型输出“成功/失败说明”。

## 2. 当前行为说明（重点）

- 当命令触发确认网关时，工具结果返回 `PENDING_CONFIRMATION` 且 `executed=false`，表示**尚未执行**。
- 用户点击确认后才会真正执行，并由后续模型回复总结执行结果。
- 模型同一轮可能触发多次 `run_local_command`，因此可能出现多个 `actionId`。

## 3. 已知问题与观察项

- 如果同轮产生多个待确认 action，前端会并列展示，需逐条确认/拒绝。
- 目前 confirm 执行结果主要通过 follow-up 聊天消息反馈；若需要更强审计，可补充专用 run event 类型。
- `fetch_page` 对部分站点可能遭遇 403（目标站反爬策略），属于上游可用性问题。

## 4. 下一步建议

1. 增加 `MCP_CONFIRM_RESULT` 事件，统一把 confirm 执行结果落库到时间线。
2. 对 pending action 做去重策略（避免模型同轮重复创建相同命令确认项）。
3. 完善高风险命令规则配置化（正则外置 + 环境级策略模板）。
4. 接入一个外部只读 MCP 作为试点，验证跨服务认证与稳定性。
