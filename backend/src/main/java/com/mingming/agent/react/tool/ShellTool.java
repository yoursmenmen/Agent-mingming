package com.mingming.agent.react.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ShellTool implements AgentTool {

    private static final List<String> WHITELIST_PREFIXES = List.of(
            "ls", "ll", "pwd", "echo", "cat", "head", "tail", "which", "type",
            "git clone", "git status", "git log", "git diff", "git fetch", "git remote",
            "node --version", "node -v", "npm --version", "npm -v",
            "java --version", "java -version", "mvn --version", "mvn -v",
            "python --version", "python3 --version", "pip --version",
            "find", "tree", "wc", "sort", "uniq", "grep",
            "curl --version", "docker --version",
            "uname", "hostname", "whoami", "date", "uptime");

    /** 命令中若含有这些 flag，视为只读操作，自动放行（不走确认流程）。 */
    private static final List<String> READONLY_FLAGS = List.of(
            "--dry-run", "--list", "--list-files", "-n", "--check");

    private static final List<String> BLACKLIST_PATTERNS = List.of(
            "rm -rf /", "rm -rf /*", "rm -rf ~",
            "mkfs", "shutdown", "reboot", "halt", "poweroff",
            "dd if=/dev/zero", ":(){ :|:& };:");

    @Override
    public String name() { return "shell_exec"; }

    @Override
    public String description() {
        return "执行 shell 命令。安全命令（ls/git clone 等）自动执行；npm install/文件写操作等需用户确认；危险命令直接拒绝。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{"command":{"type":"string","description":"要执行的 shell 命令"},"workDir":{"type":"string","description":"工作目录（可选）"}},"required":["command"]}
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object cmdObj = args.get("command");
        if (!(cmdObj instanceof String command) || command.isBlank()) {
            return ToolResult.error("缺少 command 参数");
        }
        if (isBlacklisted(command)) {
            return ToolResult.error("禁止执行该命令（黑名单策略）：" + command);
        }
        String workDir = args.getOrDefault("workDir", System.getProperty("java.io.tmpdir")).toString();
        return runCommand(command, workDir);
    }

    public static boolean isWhitelisted(String command) {
        if (command == null) return false;
        String trimmed = command.trim().toLowerCase();
        for (String prefix : WHITELIST_PREFIXES) {
            if (trimmed.equals(prefix) || trimmed.startsWith(prefix + " ") || trimmed.startsWith(prefix + "\t")) {
                return true;
            }
        }
        // 含只读 flag 的命令（如 --dry-run）视为安全，自动放行
        for (String flag : READONLY_FLAGS) {
            if (trimmed.contains(flag)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlacklisted(String command) {
        if (command == null) return false;
        String trimmed = command.trim().toLowerCase();
        for (String pattern : BLACKLIST_PATTERNS) {
            if (trimmed.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private ToolResult runCommand(String command, String workDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("bash", "-c", command);
            }
            pb.directory(new java.io.File(workDir));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("命令执行超时（60 秒）");
            }
            int exitCode = process.exitValue();
            String out = output.toString().trim();
            if (exitCode == 0) {
                return ToolResult.success(out.isBlank() ? "(命令执行成功，无输出)" : out);
            } else {
                return ToolResult.error("exitCode=" + exitCode + (out.isBlank() ? "" : "\n" + out));
            }
        } catch (Exception e) {
            return ToolResult.error("执行异常：" + e.getMessage());
        }
    }
}
