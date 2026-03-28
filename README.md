# Agent_mm (AI Agent Monorepo MVP)

Monorepo for an AI Agent system using **Spring Boot + Spring AI Alibaba (DashScope/Bailian)** and a **Vue3 + Vite** console.

> Chinese version: see **README.zh-CN.md**.

## Features (current)

Backend:
- **Health**: `GET /health`
- **SSE chat**: `POST /api/chat/stream` returns SSE events
- **Run trace persistence**: PostgreSQL + Flyway migrations
- **Run events query**: `GET /api/runs/{runId}/events`
- **Simple auth**: `Authorization: Bearer <token>` (except `/health` and `/actuator/**`)
- **Skills** (tools): demo `now`, `add` via `@Tool`
- **MCP**: config + API skeleton (`/api/mcp/servers`, `/api/mcp/tools` placeholder)

Frontend:
- Vite + Vue3 TypeScript scaffold (UI wiring WIP)

Docs:
- See `docs/README.md`

## Prerequisites

- JDK 21 (you have it at `C:\Env\Java\Java21`)
- Maven 3.9+
- Node 20+
- Docker (via WSL) for PostgreSQL

## Start PostgreSQL

From repo root:

```bash
# run this in WSL where docker works
cd /c/DevApp/MyResp/MyJavaProject/Agent_mm
docker compose up -d
```

Default DB config in `docker-compose.yml`:
- DB: `agentdb`
- user: `agent`
- password: `agent`
- port: `5432`

## Start backend

From repo root:

```bash
export JAVA_HOME="/c/Env/Java/Java21"
export PATH="$JAVA_HOME/bin:$PATH"

cd backend

# required
export AI_DASHSCOPE_API_KEY="<your_key>"
export AGENT_API_TOKEN="dev-token-change-me"

# optional override
# export DB_URL="jdbc:postgresql://localhost:5432/agentdb"
# export DB_USER="agent"
# export DB_PASSWORD="agent"

mvn spring-boot:run
```

Backend runs on: `http://localhost:18080`

## Try the SSE chat API

Example (curl):

```bash
curl -N \
  -H "Authorization: Bearer dev-token-change-me" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:18080/api/chat/stream" \
  -d '{"message":"hello"}'
```

You should see events like:
- `event: run` with a `runId`
- `event: event` with model output payload

Then query stored events:

```bash
curl \
  -H "Authorization: Bearer dev-token-change-me" \
  "http://localhost:18080/api/runs/<runId>/events"
```

## Start frontend (dev)

```bash
cd frontend
npm install
npm run dev
```

## Notes

- `POST /api/chat/stream` is SSE over POST. Browser native `EventSource` only supports GET.
  - Frontend will use `fetch()` + ReadableStream parsing (recommended) or we can switch backend to GET.

## Documentation

- `docs/README.md`
- `docs/project-overview.md`
- `docs/changes-log.md`
