package com.mingming.agent.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GitHubRepoSourceAdapter implements McpRepoSourceAdapter {

    private static final Pattern REPO_PATTERN = Pattern.compile("https?://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/.*)?");

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    @Override
    public boolean supports(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            return false;
        }
        return REPO_PATTERN.matcher(repoUrl.trim()).matches();
    }

    @Override
    public McpRepoDescriptor resolve(String repoUrl) {
        Matcher matcher = REPO_PATTERN.matcher(repoUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("unsupported github repo url");
        }
        String owner = matcher.group(1);
        String repo = matcher.group(2);
        if (repo.toLowerCase(Locale.ROOT).endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        String cloneUrl = "https://github.com/" + owner + "/" + repo + ".git";
        String readme = fetchReadme(owner, repo);
        return new McpRepoDescriptor("github", owner, repo, cloneUrl, readme);
    }

    private String fetchReadme(String owner, String repo) {
        List<String> urls = List.of(
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/README.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/master/README.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/README.md");
        for (String url : urls) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "text/plain")
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300 && response.body() != null) {
                    return response.body();
                }
            } catch (Exception ignored) {
                // continue fallback
            }
        }
        return "";
    }
}
