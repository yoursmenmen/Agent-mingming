# RAG 同步可观测与多知识源接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给向量 RAG 增加同步状态可观测与 URL 知识源接入，并保持稳定降级。

**Architecture:** 在现有向量 RAG 基础上新增 Sync Status Service、`/api/rag/sync/*` 接口和 `RAG_SYNC` 事件；新增 URL Source Ingestion 将外部内容归一化为 chunk，进入现有增量同步与混合检索流程。

**Tech Stack:** Spring Boot, pgvector, Flyway, Spring WebClient, JUnit5, Vue3（最小展示）。

---

### Task 1: 同步状态模型与接口

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/rag/SyncStatusService.java`
- Create: `backend/src/main/java/com/mingming/agent/controller/RagSyncController.java`
- Create: `backend/src/test/java/com/mingming/agent/controller/RagSyncControllerTest.java`

- [ ] 写失败测试：状态查询与触发接口返回结构。
- [ ] 运行失败测试并确认失败原因正确。
- [ ] 实现最小功能（`GET status` + `POST trigger`）。
- [ ] 运行测试确认通过。

### Task 2: RAG_SYNC 事件与状态生命周期

**Files:**
- Modify: `backend/src/main/java/com/mingming/agent/event/RunEventType.java`
- Modify: `backend/src/main/java/com/mingming/agent/rag/VectorRagBootstrapSync.java`
- Modify: `backend/src/main/java/com/mingming/agent/rag/RetrievalEventService.java`
- Create: `backend/src/test/java/com/mingming/agent/rag/VectorRagSyncLifecycleTest.java`

- [ ] 写失败测试：`running -> completed/failed` 状态流转。
- [ ] 实现生命周期状态写入与 `RAG_SYNC` 事件。
- [ ] 验证失败分支不会中断主服务。

### Task 3: URL 知识源接入

**Files:**
- Create: `backend/src/main/java/com/mingming/agent/rag/source/UrlSourceProperties.java`
- Create: `backend/src/main/java/com/mingming/agent/rag/source/UrlSourceIngestionService.java`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/mingming/agent/rag/source/UrlSourceIngestionServiceTest.java`

- [ ] 写失败测试：URL 源抓取、清洗、chunk 输出。
- [ ] 实现 URL 抓取与文本归一化。
- [ ] 接入现有 chunk/embedding 增量同步。

### Task 4: 数据模型扩展（source_type/source_id）

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__rag_source_columns.sql`
- Modify: `backend/src/main/java/com/mingming/agent/entity/DocChunkEntity.java`
- Modify: `backend/src/main/java/com/mingming/agent/rag/VectorChunkSyncService.java`
- Create: `backend/src/test/java/com/mingming/agent/rag/VectorChunkSourceFieldsTest.java`

- [ ] 写失败测试：source 字段写入与查询过滤。
- [ ] 实现迁移与实体字段。
- [ ] 验证旧数据兼容。

### Task 5: 前端最小展示 + 文档收尾

**Files:**
- Modify: `frontend/src/services/eventMapper.ts`
- Modify: `frontend/src/services/eventMapper.test.ts`
- Modify: `docs/project-overview.md`
- Modify: `docs/config-reference.md`
- Create: `docs/superpowers/reports/2026-04-01-rag-sync-multisource-report.md`

- [ ] 增加 `RAG_SYNC` 与同步状态摘要展示。
- [ ] 更新配置文档与项目进度。
- [ ] 跑全量验证：backend tests + frontend test/build/lint。

## 完成后验证命令

- `cd backend && mvn test`
- `cd frontend && npm run test:unit && npm run build && npm run lint`
