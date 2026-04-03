package com.mingming.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class McpOnboardingService {

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("```(?:bash|sh|zsh|shell|cmd|powershell)?\\s*(.*?)```", Pattern.DOTALL);
    private static final Set<String> ALLOWED_INSTALL_CMDS =
            Set.of("git", "npm", "pnpm", "yarn", "python", "python3", "pip", "pip3", "uv", "uvx", "node", "npx");

    private final List<McpRepoSourceAdapter> repoSourceAdapters;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public McpOnboardingService(List<McpRepoSourceAdapter> repoSourceAdapters) {
        this.repoSourceAdapters = repoSourceAdapters;
    }

    @Value("${agent.mcp.onboarding.workspace-root:.mcp-onboarding}")
    private String onboardingWorkspaceRoot;

    @Value("${agent.mcp.onboarding.command-timeout-seconds:120}")
    private int commandTimeoutSeconds;

    public Map<String, Object> createPlan(String repoUrl, String serverName, String preferredTransport) {
        McpRepoDescriptor repo = resolveRepo(repoUrl);
        String readme = repo.readmeText();
        List<String> installCommands = detectInstallCommands(readme);
        String startupCommand = detectStartupCommand(readme);

        String transport = normalizeTransport(preferredTransport);
        String effectiveServerName = normalizeServerName(serverName, repo.repo());
        Path repoDir = resolveRepoDirectory(repo);

        List<String> warnings = new ArrayList<>();
        if (startupCommand.isBlank()) {
            warnings.add("未自动识别启动命令，需要手工补充 command/args。");
        }
        if ("http".equals(transport)) {
            warnings.add("当前 MVP apply 仅自动写入 stdio 配置，http 需手工补充 url。");
        }

        Map<String, Object> suggestedConfig = buildSuggestedConfig(effectiveServerName, transport, repoDir, startupCommand);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repoUrl", repoUrl);
        payload.put("source", repo.source());
        payload.put("owner", repo.owner());
        payload.put("repo", repo.repo());
        payload.put("cloneUrl", repo.cloneUrl());
        payload.put("serverName", effectiveServerName);
        payload.put("preferredTransport", transport);
        payload.put("cloneDir", repoDir.toString());
        payload.put("installCommands", installCommands);
        payload.put("startupCommand", startupCommand);
        payload.put("warnings", warnings);
        payload.put("suggestedServerConfig", suggestedConfig);
        payload.put("readyToApply", "stdio".equals(transport) && !startupCommand.isBlank());
        return payload;
    }

    public Map<String, Object> applyPlan(String repoUrl, String serverName, String preferredTransport, boolean runInstall) {
        Map<String, Object> plan = createPlan(repoUrl, serverName, preferredTransport);
        String transport = String.valueOf(plan.get("preferredTransport"));
        if (!"stdio".equals(transport)) {
            return Map.of(
                    "ok", false,
                    "status", "UNSUPPORTED_TRANSPORT_FOR_APPLY",
                    "message", "MVP apply 仅支持 stdio，请先用 stdio 接入。",
                    "plan", plan);
        }

        String startupCommand = String.valueOf(plan.get("startupCommand"));
        if (startupCommand.isBlank()) {
            return Map.of(
                    "ok", false,
                    "status", "MISSING_STARTUP_COMMAND",
                    "message", "未识别启动命令，请先手工确认后再 apply。",
                    "plan", plan);
        }

        Path repoDir = Paths.get(String.valueOf(plan.get("cloneDir"))).toAbsolutePath().normalize();
        List<Map<String, Object>> executed = new ArrayList<>();
        String cloneUrl = String.valueOf(plan.getOrDefault("cloneUrl", plan.get("repoUrl")));
        executed.add(cloneOrPull(cloneUrl, repoDir));

        @SuppressWarnings("unchecked")
        List<String> installCommands = (List<String>) plan.get("installCommands");
        if (runInstall) {
            for (String cmd : installCommands) {
                if (cmd == null || cmd.isBlank()) {
                    continue;
                }
                executed.add(runShellCommand(repoDir, cmd));
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> suggested = (Map<String, Object>) plan.get("suggestedServerConfig");
        upsertServerConfig(suggested);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("status", "APPLIED");
        result.put("repoDir", repoDir.toString());
        result.put("runInstall", runInstall);
        result.put("executedSteps", executed);
        result.put("restartRequired", true);
        result.put("message", "已写入 mcp server 配置，重启后端后生效。");
        result.put("plan", plan);
        return result;
    }

    private McpRepoDescriptor resolveRepo(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }
        for (McpRepoSourceAdapter adapter : repoSourceAdapters) {
            if (adapter.supports(repoUrl)) {
                return adapter.resolve(repoUrl);
            }
        }
        throw new IllegalArgumentException("unsupported repo source for onboarding MVP");
    }

    private List<String> detectInstallCommands(String readme) {
        if (readme == null || readme.isBlank()) {
            return List.of();
        }
        Set<String> commands = new LinkedHashSet<>();
        for (String block : extractShellBlocks(readme)) {
            for (String line : block.split("\\R")) {
                String candidate = normalizeShellLine(line);
                if (candidate.isBlank()) {
                    continue;
                }
                String lower = candidate.toLowerCase(Locale.ROOT);
                if (lower.contains(" install") || lower.startsWith("npm i") || lower.startsWith("pip install") || lower.startsWith("uv sync")) {
                    commands.add(candidate);
                }
            }
        }
        return List.copyOf(commands);
    }

    private String detectStartupCommand(String readme) {
        if (readme == null || readme.isBlank()) {
            return "";
        }
        for (String block : extractShellBlocks(readme)) {
            for (String line : block.split("\\R")) {
                String candidate = normalizeShellLine(line);
                if (candidate.isBlank()) {
                    continue;
                }
                String lower = candidate.toLowerCase(Locale.ROOT);
                boolean looksRuntime = lower.startsWith("python ") || lower.startsWith("node ") || lower.startsWith("npx ")
                        || lower.startsWith("uvx ") || lower.startsWith("uv run ") || lower.startsWith("npm run");
                if (looksRuntime && (lower.contains("mcp") || lower.contains("server") || lower.contains("start"))) {
                    return candidate;
                }
            }
        }
        return "";
    }

    private List<String> extractShellBlocks(String readme) {
        List<String> out = new ArrayList<>();
        Matcher matcher = CODE_FENCE_PATTERN.matcher(readme);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    private String normalizeShellLine(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.trim();
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
            return "";
        }
        if (trimmed.startsWith("$") || trimmed.startsWith(">")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    private String normalizeTransport(String preferredTransport) {
        String value = preferredTransport == null ? "stdio" : preferredTransport.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "http", "stdio" -> value;
            default -> "stdio";
        };
    }

    private String normalizeServerName(String serverName, String repoName) {
        String base = (serverName == null || serverName.isBlank()) ? repoName : serverName;
        String normalized = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-").replaceAll("-+", "-");
        if (normalized.isBlank()) {
            return "mcp-auto";
        }
        return normalized;
    }

    private Path resolveRepoDirectory(McpRepoDescriptor repo) {
        Path root = Paths.get(onboardingWorkspaceRoot == null || onboardingWorkspaceRoot.isBlank() ? ".mcp-onboarding" : onboardingWorkspaceRoot);
        return root.toAbsolutePath().normalize().resolve(repo.owner() + "-" + repo.repo());
    }

    private Map<String, Object> buildSuggestedConfig(String serverName, String transport, Path repoDir, String startupCommand) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", serverName);
        payload.put("transport", transport);
        payload.put("enabled", true);
        payload.put("timeoutMs", 12000);
        payload.put("auth", Map.of("type", "none"));

        if ("stdio".equals(transport) && !startupCommand.isBlank()) {
            List<String> parts = splitCommand(startupCommand);
            if (!parts.isEmpty()) {
                payload.put("command", parts.get(0));
                payload.put("args", parts.size() > 1 ? parts.subList(1, parts.size()) : List.of());
                payload.put("workingDir", repoDir.toString());
            }
        } else if ("http".equals(transport)) {
            payload.put("url", "http://127.0.0.1:9100");
        }
        return payload;
    }

    private Map<String, Object> cloneOrPull(String repoUrl, Path repoDir) {
        try {
            Files.createDirectories(repoDir.getParent());
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create onboarding workspace: " + ex.getMessage(), ex);
        }

        if (Files.exists(repoDir.resolve(".git"))) {
            return runCommand(List.of("git", "pull", "--ff-only"), repoDir);
        }
        return runCommand(List.of("git", "clone", repoUrl, repoDir.toString()), repoDir.getParent());
    }

    private Map<String, Object> runShellCommand(Path workingDir, String shellLine) {
        List<String> command = splitCommand(shellLine);
        if (command.isEmpty()) {
            return Map.of("ok", false, "command", shellLine, "error", "empty command");
        }
        return runCommand(command, workingDir);
    }

    private Map<String, Object> runCommand(List<String> command, Path workingDir) {
        String executable = command.get(0).toLowerCase(Locale.ROOT);
        if (!ALLOWED_INSTALL_CMDS.contains(executable)) {
            return Map.of("ok", false, "command", String.join(" ", command), "error", "command not allowed in onboarding MVP");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        builder.redirectErrorStream(true);

        long start = System.currentTimeMillis();
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(Math.max(10, commandTimeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Map.of("ok", false, "command", String.join(" ", command), "error", "timeout");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String clipped = output.length() > 3000 ? output.substring(0, 3000) : output;
            return Map.of(
                    "ok", process.exitValue() == 0,
                    "command", String.join(" ", command),
                    "exitCode", process.exitValue(),
                    "elapsedMs", System.currentTimeMillis() - start,
                    "output", clipped);
        } catch (Exception ex) {
            return Map.of(
                    "ok", false,
                    "command", String.join(" ", command),
                    "elapsedMs", System.currentTimeMillis() - start,
                    "error", ex.getMessage());
        }
    }

    private void upsertServerConfig(Map<String, Object> suggested) {
        Path serversPath = resolveServersPath();
        if (serversPath == null) {
            throw new IllegalStateException("mcp servers.yml not found");
        }

        McpServersConfig config;
        try {
            if (Files.exists(serversPath)) {
                config = yamlMapper.readValue(Files.newInputStream(serversPath), McpServersConfig.class);
            } else {
                config = new McpServersConfig(List.of());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("failed to read servers.yml: " + ex.getMessage(), ex);
        }

        List<McpServerConfig> servers = new ArrayList<>();
        if (config != null && config.servers() != null) {
            servers.addAll(config.servers());
        }

        String name = String.valueOf(suggested.get("name"));
        servers.removeIf(server -> server != null && name.equals(server.name()));

        String transport = String.valueOf(suggested.get("transport"));
        String command = String.valueOf(suggested.getOrDefault("command", ""));
        String workingDir = String.valueOf(suggested.getOrDefault("workingDir", ""));
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) suggested.getOrDefault("args", List.of());
        String url = String.valueOf(suggested.getOrDefault("url", ""));

        McpServerConfig serverConfig = new McpServerConfig(
                name,
                transport,
                url,
                command,
                workingDir,
                args,
                Map.of(),
                "none",
                true,
                Integer.parseInt(String.valueOf(suggested.getOrDefault("timeoutMs", 12000))),
                new McpAuthConfig("none", "", "x-api-key"));
        servers.add(serverConfig);

        try {
            Files.createDirectories(serversPath.getParent());
            yamlMapper.writeValue(serversPath.toFile(), new McpServersConfig(servers));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to write servers.yml: " + ex.getMessage(), ex);
        }
    }

    private Path resolveServersPath() {
        Path[] candidates = new Path[] {
            Paths.get("src/main/resources/mcp/servers.yml"),
            Paths.get("backend/src/main/resources/mcp/servers.yml")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return candidates[0].toAbsolutePath().normalize();
    }

    private List<String> splitCommand(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return List.of();
        }
        return List.of(commandLine.trim().split("\\s+"));
    }

}
