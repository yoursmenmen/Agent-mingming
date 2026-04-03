# MCP 本地工具服务使用说明

本文档说明如何在本项目中启动一个本地 Python MCP 服务，并通过后端接口调用。

> 说明：当前聊天链路已经支持 MCP 工具动态注入；不再只依赖固定桥接工具。

## 1. 提供了哪些工具

脚本位置：`tools/mcp/local_ops_mcp.py`

- `fetch_page`（默认启用）
  - 输入：`url`、可选 `timeoutSec`、`maxChars`
  - 输出：清洗后的文本、`etag`、`lastModified`、状态码、耗时等
- `run_local_command`（默认禁用）
  - 用于执行本地命令，需显式打开开关
  - 仅允许白名单命令
- `k8s_cluster_status`（默认禁用）
  - 只读查询 K8s pods/deployments 状态，依赖本机 `kubectl`

## 2. 启动服务

在仓库根目录执行：

```bash
python tools/mcp/local_ops_mcp.py
```

默认监听：`127.0.0.1:9100`

健康检查：

```bash
curl http://127.0.0.1:9100/health
```

可选鉴权（用于本地先学习 HTTP MCP auth）：

```bash
# 关闭鉴权（默认）
export MCP_AUTH_MODE=none

# 或 Bearer Token
export MCP_AUTH_MODE=bearer
export MCP_AUTH_BEARER_TOKEN="local-mcp-token"

# 或 API Key
export MCP_AUTH_MODE=apikey
export MCP_AUTH_API_KEY="local-mcp-key"
export MCP_AUTH_API_KEY_HEADER="x-api-key"
```

## 3. 后端接入配置

文件：`backend/src/main/resources/mcp/servers.yml`

将以下配置中的 `enabled` 改为 `true`：

```yaml
servers:
  - name: local-ops
    transport: http
    url: http://127.0.0.1:9100
    streaming: none
    enabled: true
    timeoutMs: 12000
    auth:
      type: bearer
      tokenEnv: MCP_LOCAL_OPS_BEARER_TOKEN
```

注意：`tokenEnv` 写的是“环境变量名”，不是 token 明文值。

然后重启后端。

如果你在本地 MCP 启用了 API Key，可以这样配：

```yaml
servers:
  - name: local-ops
    transport: http
    url: http://127.0.0.1:9100
    streaming: none
    enabled: true
    timeoutMs: 12000
    auth:
      type: apikey
      tokenEnv: MCP_LOCAL_OPS_API_KEY
      headerName: x-api-key
```

说明：

- `tokenEnv` 指环境变量名，后端在运行时读取；
- `token` 也支持直接写入配置（仅建议本地临时调试，不建议提交仓库）。

### 3.1 stdio 方式（学习 MCP 传输层可选）

如果你要体验 stdio 传输（不经过 HTTP）：

```yaml
servers:
  - name: local-ops-stdio
    transport: stdio
    command: python
    args:
      - tools/mcp/local_ops_mcp.py
    enabled: true
    timeoutMs: 12000
    auth:
      type: none
```

说明：

- 当前实现已升级为“按 server 常驻进程并复用会话”；
- 当进程异常退出时会自动失效并在下一次调用重建；
- 生产场景仍建议结合健康检查、重启策略与隔离部署。

## 4. 从后端接口调用

### 4.1 查看可用 MCP 工具

```bash
curl -H "Authorization: Bearer dev-token-change-me" \
  http://localhost:18080/api/mcp/tools
```

如果你直接调用本地 MCP 服务（绕过后端）做鉴权验证：

```bash
# Bearer 示例
curl -X POST "http://127.0.0.1:9100" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer local-mcp-token" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}'

# API Key 示例
curl -X POST "http://127.0.0.1:9100" \
  -H "Content-Type: application/json" \
  -H "x-api-key: local-mcp-key" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}'
```

未携带或携带错误凭据会返回 HTTP `401` 与 JSON-RPC error `-32003 unauthorized`。

### 4.2 调用 `fetch_page`

```bash
curl -X POST \
  -H "Authorization: Bearer dev-token-change-me" \
  -H "Content-Type: application/json" \
  http://localhost:18080/api/mcp/tools/call \
  -d '{
    "server": "local-ops",
    "toolName": "fetch_page",
    "arguments": {
      "url": "https://docs.spring.io/spring-ai/reference/index.html",
      "maxChars": 8000
    }
  }'
```

## 5. 聊天链路动态注入策略（推荐先配置）

动态注入配置位于 `backend/src/main/resources/application.yml`：

```yaml
agent:
  mcp:
    runtime:
      enabled: true
      allow-tools: ""
      deny-tools: run_local_command
      max-callbacks: 32
```

等价环境变量：

- `AGENT_MCP_RUNTIME_ENABLED`
- `AGENT_MCP_RUNTIME_ALLOW_TOOLS`
- `AGENT_MCP_RUNTIME_DENY_TOOLS`
- `AGENT_MCP_RUNTIME_MAX_CALLBACKS`

规则说明：

- `deny-tools` 优先级高于 `allow-tools`
- 名称可写 `tool`（全局）或 `server:tool`（按 server 精确匹配）
- `allow-tools` 为空表示默认放行（再叠加 deny）

示例（仅允许只读工具）：

```bash
export AGENT_MCP_RUNTIME_ALLOW_TOOLS="fetch_page,local-ops:k8s_cluster_status"
export AGENT_MCP_RUNTIME_DENY_TOOLS="run_local_command"
```

## 6. 命令二次确认（run_local_command）

后端会对 `run_local_command` 做分级：

- **硬拦截**：明显破坏性模式（如 `rm *`、`rm -rf /`）
- **待确认**：安装/变更类命令（如 `apt install`、`pip install`、`kubectl apply`）

相关配置：

```yaml
agent:
  mcp:
    confirmation:
      enabled: true
      pending-ttl-seconds: 300
```

等价环境变量：

- `AGENT_MCP_CONFIRMATION_ENABLED`
- `AGENT_MCP_CONFIRMATION_PENDING_TTL_SECONDS`

当工具返回 `PENDING_CONFIRMATION` 时，可用以下接口确认：

```bash
curl -X POST -H "Authorization: Bearer dev-token-change-me" \
  http://localhost:18080/api/mcp/actions/<actionId>/confirm
```

拒绝：

```bash
curl -X POST -H "Authorization: Bearer dev-token-change-me" \
  http://localhost:18080/api/mcp/actions/<actionId>/reject
```

查看待确认队列：

```bash
curl -H "Authorization: Bearer dev-token-change-me" \
  http://localhost:18080/api/mcp/actions/pending
```

## 7. 高风险工具开关（本地调试专用）

默认情况下，命令执行和集群状态查询是关闭的。

### 开启本地命令工具

```bash
export MCP_ENABLE_LOCAL_EXEC=true
python tools/mcp/local_ops_mcp.py
```

### 开启只读 K8s 工具

```bash
export MCP_ENABLE_K8S_READONLY=true
python tools/mcp/local_ops_mcp.py
```

## 8. 通过 SSH 在远端服务器执行命令（含 K8s）

如果你希望工具不是在本机执行，而是先 SSH 到你的服务器执行：

```bash
export MCP_EXEC_MODE=ssh
export MCP_SSH_HOST=10.0.0.8
export MCP_SSH_PORT=22
export MCP_SSH_USER=ubuntu
export MCP_SSH_KEY_PATH=/path/to/id_rsa
export MCP_SSH_AUTH_MODE=key
export MCP_SSH_STRICT_HOST_KEY_CHECKING=accept-new

export MCP_ENABLE_LOCAL_EXEC=true
export MCP_ENABLE_K8S_READONLY=true
python tools/mcp/local_ops_mcp.py
```

说明：

- `MCP_EXEC_MODE=ssh` 后，`run_local_command` 和 `k8s_cluster_status` 都会在远端执行。
- K8s 集群地址和端口通常由远端服务器上的 `kubectl config` 管理，不需要在 MCP 再单独配置 API Server 地址。
- 前提是远端机器已经配置好 `kubectl` 凭据与上下文。

如果你要用用户名+密码（不推荐，但可用）：

```bash
export MCP_EXEC_MODE=ssh
export MCP_SSH_HOST=10.0.0.8
export MCP_SSH_PORT=22
export MCP_SSH_USER=ubuntu
export MCP_SSH_AUTH_MODE=password
export MCP_SSH_PASSWORD='your_password'
export MCP_KUBECONFIG=/etc/kubernetes/admin.conf
export MCP_ENABLE_LOCAL_EXEC=true
python tools/mcp/local_ops_mcp.py
```

注意：密码模式依赖 `sshpass`，若缺失会报错 `sshpass not found`。

如果远端 `kubectl` 依赖非默认 kubeconfig，请设置 `MCP_KUBECONFIG`，否则非交互 SSH 可能拿不到正确上下文。

## 9. 可观测与排障

每次聊天 run 启动时会写入一条 `MCP_TOOLS_BOUND` 事件，包含：

- 本次注入的 MCP 工具列表（`callbackName/server/tool/required`）
- 被策略拦截的工具和原因（`denied-by-policy` / `not-in-allowlist` / `max-callbacks-exceeded`）
- MCP discovery 错误

可通过 `GET /api/runs/{runId}/events` 查看。

## 10. 安全边界说明

- `run_local_command` 不使用 shell，避免注入（`shell=False`）
- 命令必须在白名单内
- 工具有执行超时限制
- 输出做了长度裁剪

> 建议：即使是本地测试，也尽量先用 `fetch_page` 和只读工具，不要默认开启命令执行。

## 11. MCP Onboarding MVP（给 GitHub 链接生成接入计划）

你也可以直接在聊天里让 Agent 执行：

- `请为 https://github.com/arjun1194/insta-mcp 生成接入计划，先不要执行`
- 确认计划后再说：`我同意，执行接入（runInstall=false）`

Agent 内部会调用：

- `mcp_onboarding_plan`
- `mcp_onboarding_apply`（需要 `approved=true` 才会执行）

### 11.1 生成计划

```bash
curl -X POST \
  -H "Authorization: Bearer dev-token-change-me" \
  -H "Content-Type: application/json" \
  http://localhost:18080/api/mcp/onboarding/plan \
  -d '{
    "repoUrl": "https://github.com/arjun1194/insta-mcp",
    "serverName": "insta-mcp",
    "preferredTransport": "stdio"
  }'
```

### 11.2 执行 apply（MVP）

```bash
curl -X POST \
  -H "Authorization: Bearer dev-token-change-me" \
  -H "Content-Type: application/json" \
  http://localhost:18080/api/mcp/onboarding/apply \
  -d '{
    "repoUrl": "https://github.com/arjun1194/insta-mcp",
    "serverName": "insta-mcp",
    "preferredTransport": "stdio",
    "runInstall": false
  }'
```

说明：

- MVP 目前优先支持 `stdio` 自动接入；
- 会 clone/pull 到 `AGENT_MCP_ONBOARDING_WORKSPACE_ROOT`，并写入 `mcp/servers.yml`；
- 建议先 `runInstall=false` 看计划，再按需开启安装命令执行。
