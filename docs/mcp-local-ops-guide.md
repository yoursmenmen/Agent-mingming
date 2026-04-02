# MCP 本地工具服务使用说明

本文档说明如何在本项目中启动一个本地 Python MCP 服务，并通过后端接口调用。

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

## 5. 高风险工具开关（本地调试专用）

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

## 6. 通过 SSH 在远端服务器执行命令（含 K8s）

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

## 7. 安全边界说明

- `run_local_command` 不使用 shell，避免注入（`shell=False`）
- 命令必须在白名单内
- 工具有执行超时限制
- 输出做了长度裁剪

> 建议：即使是本地测试，也尽量先用 `fetch_page` 和只读工具，不要默认开启命令执行。
