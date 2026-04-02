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
```

然后重启后端。

## 4. 从后端接口调用

### 4.1 查看可用 MCP 工具

```bash
curl -H "Authorization: Bearer dev-token-change-me" \
  http://localhost:18080/api/mcp/tools
```

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

## 6. 高风险工具开关（本地调试专用）

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

## 7. 通过 SSH 在远端服务器执行命令（含 K8s）

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

## 8. 可观测与排障

每次聊天 run 启动时会写入一条 `MCP_TOOLS_BOUND` 事件，包含：

- 本次注入的 MCP 工具列表（`callbackName/server/tool/required`）
- 被策略拦截的工具和原因（`denied-by-policy` / `not-in-allowlist` / `max-callbacks-exceeded`）
- MCP discovery 错误

可通过 `GET /api/runs/{runId}/events` 查看。

## 9. 安全边界说明

- `run_local_command` 不使用 shell，避免注入（`shell=False`）
- 命令必须在白名单内
- 工具有执行超时限制
- 输出做了长度裁剪

> 建议：即使是本地测试，也尽量先用 `fetch_page` 和只读工具，不要默认开启命令执行。
