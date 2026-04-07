package com.mingming.agent.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
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
    private final ObjectMapper yamlMapper;

    public McpOnboardingService(List<McpRepoSourceAdapter> repoSourceAdapters) {
        this.repoSourceAdapters = repoSourceAdapters;
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.INDENT_ARRAYS)
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
        this.yamlMapper = new ObjectMapper(yamlFactory);
        this.yamlMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.yamlMapper.enable(SerializationFeature.INDENT_OUTPUT);
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
        List<String> requiredEnv = detectRequiredEnvVariables(readme);
        List<String> missingRequiredEnv = detectMissingEnv(requiredEnv);

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

        Map<String, Object> suggestedConfig = buildSuggestedConfig(effectiveServerName, transport, repoDir, startupCommand, requiredEnv);
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
        payload.put("requiredEnv", requiredEnv);
        payload.put("missingRequiredEnv", missingRequiredEnv);
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

        @SuppressWarnings("unchecked")
        List<String> requiredEnv = (List<String>) plan.getOrDefault("requiredEnv", List.of());
        List<String> missingRequiredEnv = detectMissingEnv(requiredEnv);

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
        result.put("status", missingRequiredEnv.isEmpty() ? "APPLIED" : "APPLIED_WITH_WARNINGS");
        result.put("repoDir", repoDir.toString());
        result.put("runInstall", runInstall);
        result.put("executedSteps", executed);
        result.put("restartRequired", true);
        if (missingRequiredEnv.isEmpty()) {
            result.put("message", "已写入 mcp server 配置，重启后端后生效。");
        } else {
            result.put("message", "已写入 mcp server 配置，但仍缺少环境变量: " + String.join(", ", missingRequiredEnv));
            result.put("missingRequiredEnv", missingRequiredEnv);
        }
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

        List<CandidateCommand> candidates = new ArrayList<>();
        for (String block : extractShellBlocks(readme)) {
            for (String line : block.split("\\R")) {
                String candidate = normalizeShellLine(line);
                if (candidate.isBlank()) {
                    continue;
                }
                String lower = candidate.toLowerCase(Locale.ROOT);
                boolean looksRuntime = lower.startsWith("python ") || lower.startsWith("node ") || lower.startsWith("npx ")
                        || lower.startsWith("uvx ") || lower.startsWith("uv run ") || lower.startsWith("npm run");
                if (!looksRuntime) {
                    continue;
                }

                int score = 0;
                if (lower.contains("mcp")) {
                    score += 5;
                }
                if (lower.contains("server")) {
                    score += 4;
                }
                if (lower.contains("start")) {
                    score += 3;
                }
                if (lower.startsWith("npm run dev") || lower.startsWith("pnpm dev") || lower.startsWith("yarn dev")) {
                    score += 3;
                }
                if (lower.contains("dev")) {
                    score += 1;
                }
                candidates.add(new CandidateCommand(candidate, score));
            }
        }

        return candidates.stream()
                .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                .map(CandidateCommand::command)
                .findFirst()
                .orElse("");
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
        trimmed = trimmed.replaceAll("\\s+#.*$", "").trim();
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

    private Map<String, Object> buildSuggestedConfig(
            String serverName,
            String transport,
            Path repoDir,
            String startupCommand,
            List<String> requiredEnv) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", serverName);
        payload.put("transport", transport);
        payload.put("enabled", true);
        payload.put("timeoutMs", 12000);
        payload.put("auth", Map.of("type", "none"));

        if ("stdio".equals(transport) && !startupCommand.isBlank()) {
            List<String> parts = splitCommand(startupCommand);
            if (!parts.isEmpty()) {
                payload.put("command", normalizeExecutable(parts.get(0)));
                payload.put("args", parts.size() > 1 ? parts.subList(1, parts.size()) : List.of());
                payload.put("workingDir", repoDir.toString());
                payload.put("env", buildEnvPlaceholders(requiredEnv));
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

        List<String> effectiveCommand = normalizeInstallCommand(command);
        ProcessBuilder builder = new ProcessBuilder(effectiveCommand);
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
                    "command", String.join(" ", effectiveCommand),
                    "exitCode", process.exitValue(),
                    "elapsedMs", System.currentTimeMillis() - start,
                    "output", clipped);
        } catch (Exception ex) {
            return Map.of(
                    "ok", false,
                    "command", String.join(" ", effectiveCommand),
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
            servers.addAll(config.servers().stream()
                    .map(this::sanitizeServerConfig)
                    .filter(item -> item != null)
                    .toList());
        }

        String name = String.valueOf(suggested.get("name"));
        servers.removeIf(server -> server != null && name.equals(server.name()));

        String transport = String.valueOf(suggested.get("transport"));
        String command = String.valueOf(suggested.getOrDefault("command", ""));
        String workingDir = String.valueOf(suggested.getOrDefault("workingDir", ""));
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) suggested.getOrDefault("args", List.of());
        String url = String.valueOf(suggested.getOrDefault("url", ""));
        @SuppressWarnings("unchecked")
        Map<String, String> suggestedEnv = (Map<String, String>) suggested.getOrDefault("env", Map.of());

        String normalizedTransport = transport == null ? "" : transport.trim().toLowerCase(Locale.ROOT);
        String effectiveUrl = "http".equals(normalizedTransport) ? (url == null || url.isBlank() ? null : url) : null;
        String effectiveCommand = "stdio".equals(normalizedTransport) ? (command == null || command.isBlank() ? null : command) : null;
        String effectiveWorkingDir = "stdio".equals(normalizedTransport)
                ? (workingDir == null || workingDir.isBlank() ? null : workingDir)
                : null;
        List<String> effectiveArgs = "stdio".equals(normalizedTransport) ? (args == null ? List.of() : args) : null;
        Map<String, String> effectiveEnv = "stdio".equals(normalizedTransport)
                ? (suggestedEnv == null ? Map.of() : suggestedEnv)
                : null;

        McpServerConfig serverConfig = new McpServerConfig(
                name,
                normalizedTransport,
                effectiveUrl,
                effectiveCommand,
                effectiveWorkingDir,
                effectiveArgs,
                effectiveEnv,
                "none",
                true,
                Integer.parseInt(String.valueOf(suggested.getOrDefault("timeoutMs", 12000))),
                new McpAuthConfig("none", null, null, null));
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

    private List<String> detectRequiredEnvVariables(String readme) {
        if (readme == null || readme.isBlank()) {
            return List.of();
        }

        Set<String> vars = new LinkedHashSet<>();
        Pattern tablePattern = Pattern.compile("\\|\\s*`([A-Z][A-Z0-9_]{2,})`\\s*\\|");
        Matcher tableMatcher = tablePattern.matcher(readme);
        while (tableMatcher.find()) {
            vars.add(tableMatcher.group(1));
        }

        Pattern envLinePattern = Pattern.compile("(?m)^\\s*([A-Z][A-Z0-9_]{2,})\\s*=");
        Matcher envLineMatcher = envLinePattern.matcher(readme);
        while (envLineMatcher.find()) {
            vars.add(envLineMatcher.group(1));
        }

        return vars.stream()
                .filter(var -> !var.startsWith("MCP_"))
                .toList();
    }

    private List<String> detectMissingEnv(List<String> requiredEnv) {
        if (requiredEnv == null || requiredEnv.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String env : requiredEnv) {
            if (env == null || env.isBlank()) {
                continue;
            }
            String value = System.getenv(env);
            if (value == null || value.isBlank()) {
                missing.add(env);
            }
        }
        return List.copyOf(missing);
    }

    private Map<String, String> buildEnvPlaceholders(List<String> requiredEnv) {
        if (requiredEnv == null || requiredEnv.isEmpty()) {
            return Map.of();
        }
        Map<String, String> env = new LinkedHashMap<>();
        for (String item : requiredEnv) {
            if (item == null || item.isBlank()) {
                continue;
            }
            env.put(item, "${" + item + "}");
        }
        return env;
    }

    private List<String> normalizeInstallCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        String executable = command.get(0);
        if (isWindows() && ("npm".equalsIgnoreCase(executable) || "pnpm".equalsIgnoreCase(executable) || "yarn".equalsIgnoreCase(executable))) {
            return List.of("cmd", "/c", String.join(" ", command));
        }
        List<String> out = new ArrayList<>(command);
        out.set(0, normalizeExecutable(out.get(0)));
        return out;
    }

    private String normalizeExecutable(String executable) {
        if (executable == null || executable.isBlank()) {
            return executable;
        }
        if (!isWindows()) {
            return executable;
        }
        if ("npm".equalsIgnoreCase(executable)) {
            return "npm.cmd";
        }
        if ("pnpm".equalsIgnoreCase(executable)) {
            return "pnpm.cmd";
        }
        if ("yarn".equalsIgnoreCase(executable)) {
            return "yarn.cmd";
        }
        return executable;
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private McpServerConfig sanitizeServerConfig(McpServerConfig input) {
        if (input == null) {
            return null;
        }
        McpAuthConfig auth = input.auth();
        McpAuthConfig safeAuth = auth == null
                ? new McpAuthConfig("none", null, null, null)
                : new McpAuthConfig(
                        auth.type() == null || auth.type().isBlank() ? "none" : auth.type(),
                        auth.tokenEnv(),
                        auth.token(),
                        auth.headerName());

        return new McpServerConfig(
                input.name() == null ? "" : input.name(),
                input.transport() == null ? "" : input.transport(),
                input.url(),
                input.command(),
                input.workingDir(),
                input.args(),
                input.env(),
                input.streaming() == null ? "none" : input.streaming(),
                input.enabled(),
                input.timeoutMs() <= 0 ? 12000 : input.timeoutMs(),
                safeAuth);
    }

    private record CandidateCommand(String command, int score) {}

}
