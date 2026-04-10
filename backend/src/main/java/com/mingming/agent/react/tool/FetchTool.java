package com.mingming.agent.react.tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class FetchTool implements AgentTool {

    private static final int TIMEOUT_SECONDS = 30;

    @Override
    public String name() { return "fetch_page"; }

    @Override
    public String description() {
        return "获取指定 URL 的网页内容（文本）。适用于阅读 GitHub README、文档页面、API 参考等。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{"url":{"type":"string","description":"要获取的网页 URL"}},"required":["url"]}
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        Object urlObj = args.get("url");
        if (!(urlObj instanceof String url) || url.isBlank()) {
            return ToolResult.error("缺少 url 参数");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
            if (!uri.isAbsolute()) return ToolResult.error("URL 必须以 http:// 或 https:// 开头");
        } catch (IllegalArgumentException e) {
            return ToolResult.error("无效 URL：" + e.getMessage());
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .header("User-Agent", "Mozilla/5.0 (compatible; AgentBot/1.0)")
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return ToolResult.success(stripHtml(response.body()));
            }
            return ToolResult.error("HTTP " + response.statusCode() + "：" + url);
        } catch (Exception e) {
            return ToolResult.error("请求失败：" + e.getMessage());
        }
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("\\s{3,}", "\n").trim();
    }
}
