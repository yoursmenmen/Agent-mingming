# 配置参考（Configuration Reference）

## 后端环境变量

| 名称 | 作用 | 示例 |
|---|---|---|
| `AI_DASHSCOPE_API_KEY` | Spring AI Alibaba 使用的 DashScope/百炼 API Key | `xxxx` |
| `AGENT_API_TOKEN` | 简单鉴权 token，保护所有 `/api/**` 接口 | `dev-token-change-me` |
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/agentdb` |
| `DB_USER` | DB 用户名 | `agent` |
| `DB_PASSWORD` | DB 密码 | `agent` |
| `AGENT_RAG_VECTOR_ENABLED` | 是否启用向量 RAG | `true` |
| `AGENT_RAG_VECTOR_DOCS_ROOT` | docs 扫描根目录（相对进程工作目录） | `docs` / `../docs` |
| `AGENT_RAG_VECTOR_EMBEDDING_MODEL` | 向量模型标识（用于 embedding 生成与过滤） | `text-embedding-v3` |
| `AGENT_RAG_VECTOR_EMBEDDING_VERSION` | 向量版本标识（用于增量更新判定） | `2026-03` |

## `backend/src/main/resources/application.yml`

- `server.port`：后端端口（默认 `18080`）
- `spring.ai.dashscope.api-key`：绑定到 `AI_DASHSCOPE_API_KEY`
- `agent.security.apiToken`：绑定到 `AGENT_API_TOKEN`
- `agent.rag.vector.enabled`：向量 RAG 总开关
- `agent.rag.vector.docsRoot`：文档扫描根目录
- `agent.rag.vector.embeddingModel`：向量模型标识
- `agent.rag.vector.embeddingVersion`：向量版本标识

## 向量维度与迁移说明

- 当前向量列维度为 `1024`（`doc_chunk_embedding.embedding vector(1024)`）。
- 若切换到其他维度模型，需要新增 Flyway migration 做列类型变更与索引重建，并重建向量数据。

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
