#!/usr/bin/env python3
"""Minimal MCP-like JSON-RPC HTTP server for local testing.

Supported methods:
- tools/list
- tools/call

Provided tools:
- fetch_page (enabled by default)
- run_local_command (disabled by default, enable via MCP_ENABLE_LOCAL_EXEC=true)
- k8s_cluster_status (disabled by default, enable via MCP_ENABLE_K8S_READONLY=true)
"""

from __future__ import annotations

import html
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Dict, List, Tuple


def env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass
class Config:
    host: str = os.getenv("MCP_HOST", "127.0.0.1")
    port: int = env_int("MCP_PORT", 9100)
    max_fetch_chars: int = env_int("MCP_MAX_FETCH_CHARS", 12000)
    enable_local_exec: bool = env_bool("MCP_ENABLE_LOCAL_EXEC", True)
    enable_k8s_readonly: bool = env_bool("MCP_ENABLE_K8S_READONLY", True)
    command_timeout_sec: int = env_int("MCP_COMMAND_TIMEOUT_SEC", 12)
    fetch_timeout_sec: int = env_int("MCP_FETCH_TIMEOUT_SEC", 10)
    allow_unsafe_commands: bool = env_bool("MCP_ALLOW_UNSAFE_COMMANDS", True)
    exec_mode: str = os.getenv("MCP_EXEC_MODE", "local").strip().lower()
    ssh_host: str = os.getenv("MCP_SSH_HOST", "210.30.96.107").strip()
    ssh_port: int = env_int("MCP_SSH_PORT", 5900)
    ssh_user: str = os.getenv("MCP_SSH_USER", "root_dlutcardiff").strip()
    ssh_key_path: str = os.getenv("MCP_SSH_KEY_PATH", "").strip()
    ssh_password: str = os.getenv("MCP_SSH_PASSWORD", "Cardiff_r00t_dlut@@00")
    ssh_auth_mode: str = os.getenv("MCP_SSH_AUTH_MODE", "password").strip().lower()
    ssh_strict_host_key_checking: str = os.getenv("MCP_SSH_STRICT_HOST_KEY_CHECKING", "accept-new").strip()
    kubeconfig_path: str = os.getenv("MCP_KUBECONFIG", "/home/root_dlutcardiff/.kube").strip()
    auth_mode: str = os.getenv("MCP_AUTH_MODE", "none").strip().lower()
    auth_bearer_token: str = os.getenv("MCP_AUTH_BEARER_TOKEN", "").strip()
    auth_api_key: str = os.getenv("MCP_AUTH_API_KEY", "").strip()
    auth_api_key_header: str = os.getenv("MCP_AUTH_API_KEY_HEADER", "x-api-key").strip()


CONFIG = Config()


ToolHandler = Callable[[Dict[str, Any]], Dict[str, Any]]


def always_enabled() -> bool:
    return True


def jsonrpc_ok(req_id: Any, result: Dict[str, Any]) -> Dict[str, Any]:
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


def jsonrpc_err(req_id: Any, code: int, message: str, data: Any = None) -> Dict[str, Any]:
    payload: Dict[str, Any] = {"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}
    if data is not None:
        payload["error"]["data"] = data
    return payload


def tool_list() -> List[Dict[str, Any]]:
    return [
        spec["meta"]
        for spec in tool_registry().values()
        if spec["enabled"]()
    ]


def strip_html(raw: str) -> str:
    text = re.sub(r"(?is)<script.*?>.*?</script>", " ", raw)
    text = re.sub(r"(?is)<style.*?>.*?</style>", " ", text)
    title_match = re.search(r"(?is)<title>(.*?)</title>", text)
    title = ""
    if title_match:
        title = html.unescape(title_match.group(1)).strip()
    text = re.sub(r"(?is)<[^>]+>", " ", text)
    text = html.unescape(text)
    text = re.sub(r"\s+", " ", text).strip()
    return (title + "\n" + text).strip() if title else text


def fetch_page(arguments: Dict[str, Any]) -> Dict[str, Any]:
    url = str(arguments.get("url", "")).strip()
    if not url:
        raise ValueError("url is required")
    timeout = int(arguments.get("timeoutSec") or CONFIG.fetch_timeout_sec)
    max_chars = int(arguments.get("maxChars") or CONFIG.max_fetch_chars)

    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "AgentMM-MCP/0.1",
            "Accept": "text/html, text/plain, application/xhtml+xml, application/json",
        },
    )
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body_bytes = response.read(max_chars * 4)
            status = int(response.status)
            final_url = response.geturl()
            etag = response.headers.get("ETag", "")
            last_modified = response.headers.get("Last-Modified", "")
            content_type = response.headers.get("Content-Type", "")
    except urllib.error.HTTPError as ex:
        raise RuntimeError(f"http error {ex.code}: {ex.reason}") from ex
    except urllib.error.URLError as ex:
        raise RuntimeError(f"url error: {ex.reason}") from ex

    raw = body_bytes.decode("utf-8", errors="replace")
    cleaned = strip_html(raw)
    cleaned = cleaned[:max_chars]
    elapsed_ms = int((time.time() - started) * 1000)

    return {
        "url": final_url,
        "status": status,
        "contentType": content_type,
        "etag": etag,
        "lastModified": last_modified,
        "elapsedMs": elapsed_ms,
        "text": cleaned,
        "textLength": len(cleaned),
    }


def allowed_commands() -> Dict[str, List[str]]:
    # command -> allowed arg prefixes (empty means allow any arg token)
    return {
        "git": ["status", "log", "diff", "branch", "show", "rev-parse"],
        "kubectl": ["get", "describe", "top", "version", "config"],
        "docker": ["ps", "images", "inspect", "logs"],
        "ls": [],
        "dir": [],
        "pwd": [],
    }


def run_command(cmd: List[str], output_limit: int = 10000) -> Dict[str, Any]:
    if not cmd:
        raise ValueError("empty command")

    started = time.time()
    executed_cmd = cmd
    execution_mode = CONFIG.exec_mode
    if execution_mode == "ssh":
        if not CONFIG.ssh_host or not CONFIG.ssh_user:
            raise PermissionError("ssh mode requires MCP_SSH_HOST and MCP_SSH_USER")
        remote_cmd = shlex.join(cmd)
        ssh_cmd_base = [
            "ssh",
            "-p",
            str(CONFIG.ssh_port),
            "-o",
            f"StrictHostKeyChecking={CONFIG.ssh_strict_host_key_checking}",
        ]
        auth_mode = CONFIG.ssh_auth_mode
        if auth_mode not in {"auto", "key", "password"}:
            raise PermissionError(f"invalid MCP_SSH_AUTH_MODE: {auth_mode}")

        if auth_mode == "key" or (auth_mode == "auto" and CONFIG.ssh_key_path):
            ssh_cmd = [*ssh_cmd_base, "-o", "BatchMode=yes"]
            if CONFIG.ssh_key_path:
                ssh_cmd.extend(["-i", CONFIG.ssh_key_path])
            ssh_cmd.append(f"{CONFIG.ssh_user}@{CONFIG.ssh_host}")
            ssh_cmd.append(remote_cmd)
            executed_cmd = ssh_cmd
        else:
            if not CONFIG.ssh_password:
                raise PermissionError("password auth requires MCP_SSH_PASSWORD")
            ssh_cmd = [
                *ssh_cmd_base,
                "-o",
                "PreferredAuthentications=password",
                "-o",
                "PubkeyAuthentication=no",
                f"{CONFIG.ssh_user}@{CONFIG.ssh_host}",
                remote_cmd,
            ]
            executed_cmd = ["sshpass", "-p", CONFIG.ssh_password, *ssh_cmd]
    elif execution_mode != "local":
        raise PermissionError(f"invalid MCP_EXEC_MODE: {execution_mode}")

    try:
        completed = subprocess.run(
            executed_cmd,
            text=True,
            capture_output=True,
            timeout=CONFIG.command_timeout_sec,
            shell=False,
        )
    except FileNotFoundError as ex:
        if execution_mode == "ssh" and executed_cmd and executed_cmd[0] == "sshpass":
            raise RuntimeError("sshpass not found; install sshpass or switch to key auth") from ex
        raise
    elapsed_ms = int((time.time() - started) * 1000)
    return {
        "executionMode": execution_mode,
        "command": shlex.join(cmd),
        "executed": shlex.join(executed_cmd),
        "exitCode": completed.returncode,
        "elapsedMs": elapsed_ms,
        "stdout": completed.stdout[-output_limit:],
        "stderr": completed.stderr[-4000:],
    }


def run_local_command(arguments: Dict[str, Any]) -> Dict[str, Any]:
    if not CONFIG.enable_local_exec:
        raise PermissionError("run_local_command is disabled; set MCP_ENABLE_LOCAL_EXEC=true")

    command = str(arguments.get("command", "")).strip()
    args = arguments.get("args") or []
    if not command:
        raise ValueError("command is required")
    if not isinstance(args, list) or any(not isinstance(item, str) for item in args):
        raise ValueError("args must be a string array")

    if not CONFIG.allow_unsafe_commands:
        allow = allowed_commands()
        if command not in allow:
            raise PermissionError(f"command not allowed: {command}")
        prefixes = allow[command]
        if prefixes and args:
            first = args[0]
            if not any(first.startswith(prefix) for prefix in prefixes):
                raise PermissionError(f"first arg not allowed for {command}: {first}")

    command_to_run = command
    if os.name == "nt":
        lowered = command.lower()
        needs_cmd_suffix = lowered in {"npm", "pnpm", "yarn", "npx", "node"} and not lowered.endswith(".cmd")
        if needs_cmd_suffix:
            candidate = f"{command}.cmd"
            if shutil.which(candidate):
                command_to_run = candidate

    return run_command([command_to_run, *args])


def k8s_cluster_status(arguments: Dict[str, Any]) -> Dict[str, Any]:
    if not CONFIG.enable_k8s_readonly:
        raise PermissionError("k8s_cluster_status is disabled; set MCP_ENABLE_K8S_READONLY=true")
    namespace = str(arguments.get("namespace") or "default")
    selector = str(arguments.get("selector") or "").strip()

    kubectl_bin = ["kubectl"]
    if CONFIG.kubeconfig_path:
        kubectl_bin.extend(["--kubeconfig", CONFIG.kubeconfig_path])

    base = [*kubectl_bin, "-n", namespace]
    if selector:
        selector_args = ["-l", selector]
    else:
        selector_args = []

    pods = run_command([*base, "get", "pods", *selector_args, "-o", "wide"], output_limit=8000)
    deploy = run_command([*base, "get", "deploy", *selector_args, "-o", "wide"], output_limit=8000)

    return {
        "namespace": namespace,
        "selector": selector,
        "kubeconfig": CONFIG.kubeconfig_path,
        "pods": pods,
        "deployments": deploy,
    }


def tool_registry() -> Dict[str, Dict[str, Any]]:
    return {
        "fetch_page": {
            "enabled": always_enabled,
            "handler": fetch_page,
            "meta": {
                "name": "fetch_page",
                "description": "Fetch a documentation URL and return cleaned text plus metadata.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "url": {"type": "string", "description": "Target URL."},
                        "timeoutSec": {"type": "integer", "minimum": 1, "maximum": 60},
                        "maxChars": {"type": "integer", "minimum": 200, "maximum": 100000},
                    },
                    "required": ["url"],
                },
            },
        },
        "run_local_command": {
            "enabled": lambda: CONFIG.enable_local_exec,
            "handler": run_local_command,
            "meta": {
                "name": "run_local_command",
                "description": "Run a local/ssh command with strict whitelist (dev only).",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "command": {"type": "string"},
                        "args": {"type": "array", "items": {"type": "string"}},
                    },
                    "required": ["command"],
                },
            },
        },
        "k8s_cluster_status": {
            "enabled": lambda: CONFIG.enable_k8s_readonly,
            "handler": k8s_cluster_status,
            "meta": {
                "name": "k8s_cluster_status",
                "description": "Read-only Kubernetes status via kubectl get commands.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "namespace": {"type": "string"},
                        "selector": {"type": "string"},
                    },
                },
            },
        },
    }


def call_tool(name: str, arguments: Dict[str, Any]) -> Dict[str, Any]:
    tool = tool_registry().get(name)
    if tool is None:
        raise ValueError(f"unknown tool: {name}")
    if not tool["enabled"]():
        raise PermissionError(f"tool disabled: {name}")

    handler: ToolHandler = tool["handler"]
    result = handler(arguments)

    return {
        "content": [
            {
                "type": "text",
                "text": json.dumps(result, ensure_ascii=True),
            }
        ],
        "structuredContent": result,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "AgentMMMCP/0.1"

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        sys.stdout.write("[mcp] " + (format % args) + "\n")

    def do_GET(self) -> None:
        if self.path == "/health":
            self._write_json(
                200,
                {
                    "status": "ok",
                    "tools": [tool["name"] for tool in tool_list()],
                    "authMode": CONFIG.auth_mode,
                },
            )
            return
        self._write_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload_raw = self.rfile.read(length) if length > 0 else b"{}"
            payload = json.loads(payload_raw.decode("utf-8", errors="replace"))
            req_id = payload.get("id")
            method = payload.get("method")
            params = payload.get("params") or {}

            if not self._is_authorized_request():
                self._write_json(401, jsonrpc_err(req_id, -32003, "unauthorized"))
                return

            if method == "tools/list":
                response = jsonrpc_ok(req_id, {"tools": tool_list()})
            elif method == "tools/call":
                name = str(params.get("name", "")).strip()
                arguments = params.get("arguments") or {}
                if not name:
                    response = jsonrpc_err(req_id, -32602, "tool name is required")
                else:
                    try:
                        response = jsonrpc_ok(req_id, call_tool(name, arguments))
                    except PermissionError as ex:
                        response = jsonrpc_err(req_id, -32002, str(ex))
                    except Exception as ex:  # noqa: BLE001
                        response = jsonrpc_err(req_id, -32001, str(ex))
            elif method == "initialize":
                response = jsonrpc_ok(req_id, {"capabilities": {"tools": {}}})
            else:
                response = jsonrpc_err(req_id, -32601, f"method not found: {method}")

            self._write_json(200, response)
        except json.JSONDecodeError:
            self._write_json(400, jsonrpc_err(None, -32700, "invalid json"))
        except Exception as ex:  # noqa: BLE001
            self._write_json(500, jsonrpc_err(None, -32000, f"server error: {ex}"))

    def _write_json(self, code: int, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _is_authorized_request(self) -> bool:
        mode = CONFIG.auth_mode
        if mode == "none":
            return True
        if mode == "bearer":
            if not CONFIG.auth_bearer_token:
                return False
            auth_header = self.headers.get("Authorization", "")
            if not auth_header.startswith("Bearer "):
                return False
            token = auth_header[len("Bearer "):].strip()
            return token == CONFIG.auth_bearer_token
        if mode in {"apikey", "api_key"}:
            if not CONFIG.auth_api_key:
                return False
            header_name = CONFIG.auth_api_key_header or "x-api-key"
            return self.headers.get(header_name, "").strip() == CONFIG.auth_api_key
        return False


def main() -> None:
    server = ThreadingHTTPServer((CONFIG.host, CONFIG.port), Handler)
    print(
        json.dumps(
            {
                "message": "local mcp server started",
                "host": CONFIG.host,
                "port": CONFIG.port,
                "enableLocalExec": CONFIG.enable_local_exec,
                "enableK8sReadonly": CONFIG.enable_k8s_readonly,
                "execMode": CONFIG.exec_mode,
                "sshHost": CONFIG.ssh_host,
                "sshUser": CONFIG.ssh_user,
                "sshPort": CONFIG.ssh_port,
                "sshAuthMode": CONFIG.ssh_auth_mode,
                "kubeconfig": CONFIG.kubeconfig_path,
                "allowUnsafeCommands": CONFIG.allow_unsafe_commands,
                "authMode": CONFIG.auth_mode,
                "authApiKeyHeader": CONFIG.auth_api_key_header,
            },
            ensure_ascii=True,
        )
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
