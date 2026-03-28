# 配置参考（Configuration Reference）

## 后端环境变量

| 名称 | 作用 | 示例 |
|---|---|---|
| `AI_DASHSCOPE_API_KEY` | Spring AI Alibaba 使用的 DashScope/百炼 API Key | `xxxx` |
| `AGENT_API_TOKEN` | 简单鉴权 token，保护所有 `/api/**` 接口 | `dev-token-change-me` |
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/agentdb` |
| `DB_USER` | DB 用户名 | `agent` |
| `DB_PASSWORD` | DB 密码 | `agent` |

## `backend/src/main/resources/application.yml`

- `server.port`：后端端口（默认 `18080`）
- `spring.ai.dashscope.api-key`：绑定到 `AI_DASHSCOPE_API_KEY`
- `agent.security.apiToken`：绑定到 `AGENT_API_TOKEN`

## MCP 配置：`backend/src/main/resources/mcp/servers.yml`

当前格式（MVP 占位）：

```yaml
servers:
  - name: demo
    transport: http
    url: http://localhost:9000
    streaming: sse
    enabled: true
    timeoutMs: 10000
```

> MCP 的 tool discovery 与 tool invocation 将在下一步实现。
