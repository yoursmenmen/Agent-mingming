package com.mingming.agent.react.tool;

public record ToolResult(
        boolean success,
        String output,
        String error,
        boolean truncated) {

    private static final int MAX_OUTPUT_CHARS = 8192;

    public static ToolResult success(String output) {
        if (output != null && output.length() > MAX_OUTPUT_CHARS) {
            return new ToolResult(true, output.substring(0, MAX_OUTPUT_CHARS), null, true);
        }
        return new ToolResult(true, output, null, false);
    }

    public static ToolResult error(String error) {
        return new ToolResult(false, null, error, false);
    }

    public static ToolResult skipped(String reason) {
        return new ToolResult(false, null, reason != null ? reason : "用户跳过执行", false);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"success\":").append(success);
        if (output != null) {
            sb.append(",\"output\":").append(jsonString(output));
        }
        if (error != null) {
            sb.append(",\"error\":").append(jsonString(error));
        }
        if (truncated) {
            sb.append(",\"truncated\":true");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
