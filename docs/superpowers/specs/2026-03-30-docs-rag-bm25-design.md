# docs 分片 + BM25 最小 RAG 设计

## 1. 背景与目标

当前项目已完成 Tool Calling 与结构化卡片阶段，下一步进入 RAG 最小闭环。结合现有技术栈与迭代节奏，本阶段聚焦本地 `docs/` 目录，优先实现低风险、可观测、可演进的检索增强问答。

本阶段目标：

1. 在后端增加 `docs/` 文档分片与 BM25 检索能力。
2. 在聊天编排链路中接入检索上下文，提升回答的项目文档相关性。
3. 在 run timeline 中新增检索事件，支持回放与排查。
4. 明确向量检索升级边界，保证后续可平滑演进。

## 2. 范围与非目标

### 2.1 范围

- 数据源：仓库内 `docs/**/*.md`。
- 检索算法：BM25（关键词检索），不引入 embedding。
- 编排链路：在模型调用前检索并注入参考片段。
- 可观测性：新增 `RETRIEVAL_RESULT` 事件并落库。
- 联调验证：前端时间线可见检索命中摘要与原始 payload。

### 2.2 非目标

- 本阶段不引入向量库（pgvector）与 embedding 模型。
- 本阶段不接入外部 URL/第三方知识源。
- 本阶段不改动聊天主消息 UI 卡片体系。

## 3. 架构与执行流

1. 应用启动后构建 `DocsChunkIndex`：扫描 `docs/**/*.md` 并生成 chunk 索引。
2. 收到用户问题时，在 `AgentOrchestrator` 调用模型前执行检索。
3. 将 topN 命中 chunk 拼接为“参考资料区”注入 prompt。
4. 调用模型，维持现有 SSE 增量与最终消息持久化策略。
5. 记录 `RETRIEVAL_RESULT` 事件到 `run_event`，供时间线回放。

## 4. 分片与检索设计

### 4.1 分片规则

- 一级切分：按 `#` / `##` / `###` 标题层级划分 section。
- 二级切分：section 内按段落合并到目标长度（建议 300-700 中文字符）。
- 过长段落：继续切片；过短段落：与相邻段落合并。
- 每个 chunk 生成稳定 `chunkId = hash(docPath + headingPath + offset)`。

### 4.2 Chunk 元数据

- `chunkId`
- `docPath`
- `headingPath`
- `content`
- `tokenEstimate`（可选，用于后续 prompt 预算）
- `updatedAt`（可选，用于未来增量重建）

### 4.3 BM25 检索策略

- 查询与文档统一预处理：
  - 中文：字符 bigram（轻量实现）
  - 英文/数字：按词切分
- 计算 BM25 分数并排序。
- 默认取 `topK=5`，注入 `topN=3`。
- 支持 `scoreThreshold`，低于阈值视为无有效命中。

### 4.4 Prompt 注入

- 在系统提示或用户消息前拼接“参考资料区”。
- 每条命中片段包含来源标记：`[docPath > headingPath]`。
- 明确提示模型：优先使用参考资料；无依据时应明确说明。

## 5. 可观测性与事件模型

新增事件类型：`RETRIEVAL_RESULT`。

建议 payload：

```json
{
  "query": "用户问题文本",
  "topK": 5,
  "topN": 3,
  "hits": [
    {
      "chunkId": "...",
      "docPath": "docs/project-overview.md",
      "headingPath": "当前进度/下一阶段计划",
      "score": 8.42,
      "snippet": "RAG 最小闭环..."
    }
  ]
}
```

规则：

- 有命中时记录命中详情。
- 无命中时仍写事件（`hits: []`），避免“静默失败”。

## 6. 错误处理与降级

- 文档目录为空：记录 warning，检索返回空，主链路继续。
- 单文件解析失败：跳过该文件并记录 warning。
- 索引构建失败：检索模块自动降级为 no-op，不中断聊天。

## 7. 测试与验收

### 7.1 自动化测试

- 分片器：标题切分、长段拆分、短段合并、chunkId 稳定性。
- 检索器：BM25 排序、阈值过滤、空命中。
- 编排器：检索注入前后不破坏 SSE 与最终消息事件。
- 事件：`RETRIEVAL_RESULT` 落库 payload 结构正确。

### 7.2 联调测试

- 询问“项目下一阶段计划”应命中 `docs/project-overview.md`。
- 时间线可见 `RETRIEVAL_RESULT` 的 summary 与 raw payload。
- 无命中问题可正常回答且事件可追踪。

### 7.3 测试文档

新增 `docs/rag-fixtures/`：

1. 高相关短文档（验证精准命中）
2. 长文档（验证分片质量）
3. 干扰文档（验证排序鲁棒性）

## 8. 向量检索升级路径

本方案为向量检索预留升级点：

- 复用 chunk 数据模型与 chunkId。
- 将检索实现替换为 `embedding + vector topK`。
- 保持事件结构兼容，仅新增向量相关字段（如 `embeddingModel`、`distance`）。

## 9. 实现目标与过程记录要求

为满足“目标 + 过程都可追溯”，本阶段交付文档必须包含：

1. **实现目标记录**：
   - 本阶段目标、范围、验收标准。
2. **实现过程记录**：
   - 关键设计决策（例如为何先 BM25）。
   - 关键测试过程与结果（命令、通过/失败、修复动作）。
   - 风险与后续计划（向量检索升级条件）。

建议过程记录文件：

- `docs/superpowers/reports/2026-03-30-docs-rag-bm25-implementation-report.md`

## 10. 完成定义（DoD）

1. 可对 `docs/` 执行分片并建立检索索引。
2. 聊天链路可注入检索结果并影响回答内容。
3. `RETRIEVAL_RESULT` 事件落库并可在时间线回放。
4. 自动化测试与联调验证通过。
5. 形成目标文档 + 过程文档，满足可追溯要求。
