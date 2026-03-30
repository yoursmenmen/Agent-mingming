package com.mingming.agent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocsChunkingServiceTest {

    private final DocsChunkingService service = new DocsChunkingService();

    @Test
    void chunkMarkdown_shouldSplitByHeadingAndUseHierarchicalHeadingPath() {
        String md = """
                # 项目概览

                第一段内容。

                ## 下一阶段

                RAG 最小闭环。
                """;

        List<DocsChunk> chunks = service.chunkMarkdown(Path.of("docs/project-overview.md"), md);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).headingPath()).isEqualTo("项目概览");
        assertThat(chunks.get(1).headingPath()).isEqualTo("项目概览 > 下一阶段");
        assertThat(chunks.get(1).content()).contains("RAG 最小闭环");
    }

    @Test
    void chunkMarkdown_shouldSplitLargeSectionByParagraphAroundTargetSize() {
        String paragraph1 = "Alpha ".repeat(70);
        String paragraph2 = "Beta ".repeat(70);
        String paragraph3 = "Gamma ".repeat(70);
        String md = "# A\n\n" + paragraph1 + "\n\n" + paragraph2 + "\n\n" + paragraph3;

        List<DocsChunk> chunks = service.chunkMarkdown(Path.of("docs/a.md"), md);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(520));
        assertThat(chunks.get(0).content()).contains("Alpha");
        assertThat(chunks.get(chunks.size() - 1).content()).contains("Gamma");
    }

    @Test
    void chunkMarkdown_shouldMergeShortParagraphsWithinSameHeading() {
        String md = """
                # A

                short-one

                short-two

                short-three
                """;

        List<DocsChunk> chunks = service.chunkMarkdown(Path.of("docs/short.md"), md);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("short-one");
        assertThat(chunks.get(0).content()).contains("short-two");
        assertThat(chunks.get(0).content()).contains("short-three");
    }

    @Test
    void chunkMarkdown_shouldGenerateStableChunkIdFromPathHeadingAndOffsetContext() {
        String md = "# A\n\nHello world";

        List<DocsChunk> first = service.chunkMarkdown(Path.of("docs/a.md"), md);
        List<DocsChunk> second = service.chunkMarkdown(Path.of("docs/a.md"), md);

        assertThat(first).isNotEmpty();
        assertThat(first.get(0).chunkId()).isEqualTo(second.get(0).chunkId());

        int expectedOffset = 5;
        String expected = shortHash("docs/a.md|A|" + expectedOffset);
        assertThat(first.get(0).chunkId()).isEqualTo(expected);
    }

    @Test
    void loadChunks_shouldReadMarkdownFilesAndIgnoreUnreadableEntries(@TempDir Path tempDir) throws Exception {
        Path docsRoot = tempDir.resolve("docs");
        Files.createDirectories(docsRoot.resolve("nested"));

        Files.writeString(docsRoot.resolve("a.md"), "# A\n\nhello");
        Files.writeString(docsRoot.resolve("nested/b.md"), "# B\n\nworld");
        Files.writeString(docsRoot.resolve("note.txt"), "ignored");
        Files.createDirectory(docsRoot.resolve("bad.md"));

        List<DocsChunk> chunks = service.loadChunks(docsRoot);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.docPath()).endsWith(".md"));
        assertThat(chunks).noneSatisfy(chunk -> assertThat(chunk.docPath()).contains("note.txt"));
    }

    @Test
    void loadChunks_shouldReturnDeterministicOrderByRelativePath(@TempDir Path tempDir) throws Exception {
        Path docsRoot = tempDir.resolve("docs");
        Files.createDirectories(docsRoot.resolve("z-dir"));
        Files.createDirectories(docsRoot.resolve("a-dir"));

        Files.writeString(docsRoot.resolve("z-dir/z.md"), "# Z\n\nz");
        Files.writeString(docsRoot.resolve("a-dir/a.md"), "# A\n\na");
        Files.writeString(docsRoot.resolve("m.md"), "# M\n\nm");

        List<Path> ordered = service.discoverMarkdownPaths(docsRoot);

        assertThat(ordered.stream().map(path -> docsRoot.relativize(path).toString().replace('\\', '/')).toList())
                .containsExactly("a-dir/a.md", "m.md", "z-dir/z.md");
    }

    @Test
    void loadChunks_shouldContinueWhenSingleFileReadFails(@TempDir Path tempDir) throws Exception {
        Path docsRoot = tempDir.resolve("docs");
        Files.createDirectories(docsRoot);
        Path ok = docsRoot.resolve("ok.md");
        Path bad = docsRoot.resolve("bad.md");

        DocsChunkingService testable = new DocsChunkingService() {
            @Override
            List<Path> discoverMarkdownPaths(Path root) {
                return List.of(ok, bad);
            }

            @Override
            String readMarkdownFile(Path path) throws IOException {
                if (path.equals(bad)) {
                    throw new IOException("boom");
                }
                return "# OK\n\nkeep me";
            }
        };

        List<DocsChunk> chunks = testable.loadChunks(docsRoot);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).docPath()).isEqualTo("ok.md");
        assertThat(chunks.get(0).content()).contains("keep me");
    }

    private String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
