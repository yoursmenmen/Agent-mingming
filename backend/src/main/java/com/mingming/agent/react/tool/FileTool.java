package com.mingming.agent.react.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class FileTool implements AgentTool {

    @Override
    public String name() { return "file_op"; }

    @Override
    public String description() {
        return "文件操作：读取（read）、写入（write）、删除（delete）。读操作自动执行；写入和删除需要用户确认。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{"action":{"type":"string","enum":["read","write","delete"]},"path":{"type":"string"},"content":{"type":"string"}},"required":["action","path"]}
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object actionObj = args.get("action");
        Object pathObj = args.get("path");
        if (!(actionObj instanceof String action) || action.isBlank()) {
            return ToolResult.error("缺少 action 参数（read/write/delete）");
        }
        if (!(pathObj instanceof String pathStr) || pathStr.isBlank()) {
            return ToolResult.error("缺少 path 参数");
        }
        Path path = Path.of(pathStr);
        return switch (action) {
            case "read" -> readFile(path);
            case "write" -> writeFile(path, args.getOrDefault("content", "").toString());
            case "delete" -> deleteFile(path);
            default -> ToolResult.error("未知 action：" + action);
        };
    }

    private ToolResult readFile(Path path) {
        try {
            if (!Files.exists(path)) return ToolResult.error("文件不存在：" + path);
            return ToolResult.success(Files.readString(path));
        } catch (IOException e) {
            return ToolResult.error("读取失败：" + e.getMessage());
        }
    }

    private ToolResult writeFile(Path path, String content) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            return ToolResult.success("文件已写入：" + path + "（" + content.length() + " 字节）");
        } catch (IOException e) {
            return ToolResult.error("写入失败：" + e.getMessage());
        }
    }

    private ToolResult deleteFile(Path path) {
        try {
            if (!Files.exists(path)) return ToolResult.error("文件不存在：" + path);
            Files.delete(path);
            return ToolResult.success("文件已删除：" + path);
        } catch (IOException e) {
            return ToolResult.error("删除失败：" + e.getMessage());
        }
    }
}
