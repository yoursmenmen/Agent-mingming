# 2026-03-30 Docs RAG BM25 实施报告

## 目标

- 在现有会话编排链路中接入基于本地 `docs/` 的最小 RAG 能力（Markdown 分块 + BM25 检索）。
- 在 run-event 中新增 `RETRIEVAL_RESULT` 可观测事件，保证检索过程可回放。
- 前端时间线对检索事件提供可读摘要，避免直接暴露原始 JSON。
- 完成后端、前端全量验证命令并记录结果。

## 实施过程

### 1) 后端：文档分块与检索服务

- 新增 `backend/src/main/java/com/mingming/agent/rag/DocsChunk.java`：定义 chunk 结构（`chunkId/docPath/headingPath/content/tokenEstimate`）。
- 新增 `backend/src/main/java/com/mingming/agent/rag/DocsChunkingService.java`：
  - 扫描 Markdown 文件并按相对路径稳定排序。
  - 按标题层级维护 `headingPath`，按段落切分并做短段合并、长段二次切分（目标约 420 chars，上限 520）。
  - 通过 `docPath|headingPath|offset` 生成稳定短 hash 作为 `chunkId`。
- 新增 `backend/src/main/java/com/mingming/agent/rag/Bm25RetrieverService.java`：
  - 提供 BM25 检索（`search/retrieve`），支持 `topK` 与阈值过滤。
  - tokenization 同时覆盖英文数字 token 和中文二元组（bigram）。

### 2) 后端：检索事件落库与编排接入

- 新增 `backend/src/main/java/com/mingming/agent/rag/RetrievalEventService.java`：
  - 以 `RETRIEVAL_RESULT` 写入 run-event。
  - payload 包含 `query/hitCount/hits`，hits 仅保留元数据与 snippet（默认截断 200 chars）。
- 更新 `backend/src/main/java/com/mingming/agent/event/RunEventType.java`：增加 `RETRIEVAL_RESULT`。
- 更新 `backend/src/main/java/com/mingming/agent/orchestrator/AgentOrchestrator.java`：
  - `runOnce` 在模型调用前执行 `loadChunks -> BM25 retrieve -> record retrieval event`。
  - prompt 组装时将检索内容注入为“检索参考资料”上下文，再拼接当前用户消息。
  - 文档不可用或检索异常时降级为空检索，不阻断主链路。

### 3) 前端：检索事件摘要展示

- 更新 `frontend/src/services/eventMapper.ts`：
  - 为 `RETRIEVAL_RESULT` 增加专用摘要格式：
    - 命中：`命中 X 条 | 查询: ... | 代表文档: ...`
    - 未命中：`未命中 | 查询: ... | 代表文档: N/A`
- 新增 `frontend/src/services/eventMapper.test.ts`：覆盖命中/未命中两个关键分支。

### 4) 夹具与测试补齐

- 新增文档夹具：`docs/rag-fixtures/architecture-long.md`、`docs/rag-fixtures/release-notes.md`、`docs/rag-fixtures/noise.md`。
- 新增后端测试：
  - `backend/src/test/java/com/mingming/agent/rag/DocsChunkingServiceTest.java`
  - `backend/src/test/java/com/mingming/agent/rag/Bm25RetrieverServiceTest.java`
  - `backend/src/test/java/com/mingming/agent/rag/RetrievalEventServiceTest.java`
- 更新后端测试：`backend/src/test/java/com/mingming/agent/orchestrator/AgentOrchestratorTest.java`（校验检索事件 seq 与上下文注入）。

## 验证结果（全量命令）

### 后端

执行命令：

```bash
export JAVA_HOME="/c/Env/Java/Java21" && export PATH="$JAVA_HOME/bin:$PATH" && cd backend && mvn test
```

结果摘要：

- 构建结果：`BUILD SUCCESS`
- 测试汇总：`Tests run: 35, Failures: 0, Errors: 0, Skipped: 0`
- 关键新增相关测试：
  - `com.mingming.agent.rag.Bm25RetrieverServiceTest`（4 通过）
  - `com.mingming.agent.rag.DocsChunkingServiceTest`（7 通过）
  - `com.mingming.agent.rag.RetrievalEventServiceTest`（2 通过）
  - `com.mingming.agent.orchestrator.AgentOrchestratorTest`（8 通过）
- 备注：`DocsChunkingServiceTest` 中存在一条预期告警日志（模拟 `bad.md` 读取失败，验证服务可降级继续），不影响测试通过。

### 前端

执行命令：

```bash
cd frontend && npm run test:unit && npm run build && npm run lint
```

结果摘要：

- `npm run test:unit`：`Test Files 5 passed`，`Tests 20 passed`
- `npm run build`：Vite 构建成功（`✓ built in 613ms`）
- `npm run lint`：`eslint . --ext .ts,.tsx,.js,.jsx,.vue` 执行完成，无报错输出

## 风险与后续关注

- 当前检索数据源为运行时读取 `../docs`（回退 `docs`）；部署目录结构变化时可能导致召回为空，建议后续显式配置 docs 根路径。
- BM25 为纯词法检索，对同义改写、跨语言混合问法的鲁棒性有限；后续可加入 query rewrite 或 hybrid retrieval。
- 当前仅把检索内容注入 prompt，尚未在回答内容中做强制引用约束；后续可增加 citation 协议与前端可视化引用。
