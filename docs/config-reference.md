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
| `AGENT_RAG_SOURCE_LOCAL_DOCS_ENABLED` | 是否启用本地 docs 作为知识源 | `true` |
| `AGENT_RAG_SOURCE_URL_ENABLED` | 是否启用 URL 知识源 | `false` |
| `AGENT_RAG_SOURCE_URL_MAX_IN_MEMORY_SIZE_BYTES` | URL 抓取 WebClient 最大内存缓冲（字节） | `4194304` |
| `AGENT_RAG_SYNC_SCHEDULER_ENABLED` | 是否启用定时同步任务 | `false` |
| `AGENT_RAG_SYNC_SCHEDULER_CRON` | 定时同步 cron 表达式 | `0 0 3 ? * SUN` |
| `AGENT_RAG_SYNC_SCHEDULER_ZONE` | 定时同步时区 | `Asia/Shanghai` |

## `backend/src/main/resources/application.yml`

- `server.port`：后端端口（默认 `18080`）
- `spring.ai.dashscope.api-key`：绑定到 `AI_DASHSCOPE_API_KEY`
- `agent.security.apiToken`：绑定到 `AGENT_API_TOKEN`
- `agent.rag.vector.enabled`：向量 RAG 总开关
- `agent.rag.vector.docsRoot`：文档扫描根目录
- `agent.rag.vector.embeddingModel`：向量模型标识
- `agent.rag.vector.embeddingVersion`：向量版本标识
- `agent.rag.sources.localDocs.enabled`：本地 docs 源开关
- `agent.rag.sources.url.enabled`：URL 源总开关
- `agent.rag.sources.url.maxInMemorySizeBytes`：URL 抓取响应最大缓冲大小（默认 4MB）
- `agent.rag.sources.url.items[]`：URL 源列表（`name/url/enabled`）
- `agent.rag.sync.scheduler.enabled`：定时同步开关
- `agent.rag.sync.scheduler.cron`：定时同步 cron
- `agent.rag.sync.scheduler.zone`：定时同步时区

示例：

```yaml
agent:
  rag:
    sources:
      url:
        enabled: true
        items:
          - name: spring-docs
            url: https://docs.spring.io/spring-ai/reference/index.html
            enabled: true
```

## RAG 同步接口

- `GET /api/rag/sync/status`：查看同步状态、统计和最近错误。
- `POST /api/rag/sync/trigger`：手动触发一次同步，返回 `accepted` 与最新状态快照。
- `GET /api/rag/sources`：查看 URL 源配置与每个源最近同步状态。

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
