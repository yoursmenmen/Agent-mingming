package com.mingming.agent.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocsChunkingService {

    private static final Logger log = LoggerFactory.getLogger(DocsChunkingService.class);

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final int TARGET_CHARS = 420;
    private static final int MIN_SPLIT_CHARS = 280;
    private static final int MAX_SPLIT_CHARS = 520;
    private static final String ROOT_HEADING = "未命名章节";

    public List<DocsChunk> loadChunks(Path docsRoot) {
        if (docsRoot == null || !Files.exists(docsRoot)) {
            return List.of();
        }

        List<Path> markdownPaths;
        try {
            markdownPaths = discoverMarkdownPaths(docsRoot);
        } catch (IOException ex) {
            log.warn("Failed to walk markdown docs root: {}", docsRoot, ex);
            return List.of();
        }

        List<DocsChunk> chunks = new ArrayList<>();
        for (Path markdownPath : markdownPaths) {
            try {
                String markdown = readMarkdownFile(markdownPath);
                chunks.addAll(chunkMarkdown(docsRoot.relativize(markdownPath), markdown));
            } catch (IOException ex) {
                log.warn("Failed to read markdown file: {} (docsRoot: {})", markdownPath, docsRoot, ex);
            }
        }
        return chunks;
    }

    List<Path> discoverMarkdownPaths(Path docsRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(docsRoot)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> normalizePath(docsRoot.relativize(path))))
                    .toList();
        }
    }

    String readMarkdownFile(Path path) throws IOException {
        return Files.readString(path);
    }

    public List<DocsChunk> chunkMarkdown(Path relativePath, String markdown) {
        if (relativePath == null || markdown == null || markdown.isBlank()) {
            return List.of();
        }

        String docPath = relativePath.toString().replace('\\', '/');
        String normalizedMarkdown = markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<DocsChunk> chunks = new ArrayList<>();

        List<String> headingStack = new ArrayList<>();
        String currentHeadingPath = ROOT_HEADING;
        StringBuilder sectionBuffer = new StringBuilder();
        int sectionContentOffset = 0;

        String[] lines = normalizedMarkdown.split("\\n", -1);
        int cursor = 0;
        for (String line : lines) {
            Matcher headingMatcher = HEADING_PATTERN.matcher(line);
            if (headingMatcher.matches()) {
                flushSection(chunks, docPath, currentHeadingPath, sectionBuffer.toString(), sectionContentOffset);
                sectionBuffer.setLength(0);

                int level = headingMatcher.group(1).length();
                String heading = headingMatcher.group(2).trim();
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(heading.isBlank() ? ROOT_HEADING : heading);
                currentHeadingPath = String.join(" > ", headingStack);
                sectionContentOffset = cursor + line.length() + 1;
            } else {
                sectionBuffer.append(line).append('\n');
            }
            cursor += line.length() + 1;
        }

        flushSection(chunks, docPath, currentHeadingPath, sectionBuffer.toString(), sectionContentOffset);
        return chunks;
    }

    private void flushSection(
            List<DocsChunk> out, String docPath, String headingPath, String sectionText, int sectionContentOffset) {
        if (sectionText == null || sectionText.isBlank()) {
            return;
        }

        List<ChunkPart> parts = toChunkParts(sectionText);
        if (parts.isEmpty()) {
            return;
        }

        List<ChunkPart> splitParts = new ArrayList<>();
        for (ChunkPart part : parts) {
            splitParts.addAll(splitLargePart(part));
        }

        ChunkPart current = null;
        for (ChunkPart part : splitParts) {
            if (current == null) {
                current = part;
                continue;
            }

            int mergedLength = current.text().length() + 2 + part.text().length();
            if (mergedLength <= TARGET_CHARS) {
                current = new ChunkPart(current.startOffset(), part.endOffset(), current.text() + "\n\n" + part.text());
            } else {
                out.add(buildChunk(docPath, headingPath, current, sectionContentOffset));
                current = part;
            }
        }

        if (current != null) {
            out.add(buildChunk(docPath, headingPath, current, sectionContentOffset));
        }
    }

    private List<ChunkPart> toChunkParts(String sectionText) {
        List<ChunkPart> parts = new ArrayList<>();
        String[] lines = sectionText.split("\\n", -1);
        StringBuilder paragraph = new StringBuilder();
        int paragraphStart = -1;
        int paragraphEnd = -1;
        int cursor = 0;

        for (String line : lines) {
            if (line.trim().isBlank()) {
                if (paragraphStart >= 0 && paragraphEnd >= paragraphStart) {
                    parts.add(new ChunkPart(paragraphStart, paragraphEnd, paragraph.toString().trim()));
                    paragraph.setLength(0);
                    paragraphStart = -1;
                    paragraphEnd = -1;
                }
            } else {
                if (paragraphStart < 0) {
                    paragraphStart = cursor;
                }
                if (paragraph.length() > 0) {
                    paragraph.append('\n');
                }
                paragraph.append(line.stripTrailing());
                paragraphEnd = cursor + line.length();
            }
            cursor += line.length() + 1;
        }

        if (paragraphStart >= 0 && paragraphEnd >= paragraphStart) {
            parts.add(new ChunkPart(paragraphStart, paragraphEnd, paragraph.toString().trim()));
        }
        return parts;
    }

    private List<ChunkPart> splitLargePart(ChunkPart part) {
        if (part.text().length() <= MAX_SPLIT_CHARS) {
            return List.of(part);
        }

        List<ChunkPart> pieces = new ArrayList<>();
        int start = 0;
        String text = part.text();
        while (start < text.length()) {
            int remaining = text.length() - start;
            if (remaining <= MAX_SPLIT_CHARS) {
                pieces.add(new ChunkPart(part.startOffset() + start, part.startOffset() + text.length(), text.substring(start).trim()));
                break;
            }

            int split = chooseSplitIndex(text, start);
            String piece = text.substring(start, split).trim();
            if (!piece.isBlank()) {
                pieces.add(new ChunkPart(part.startOffset() + start, part.startOffset() + split, piece));
            }

            start = split;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }

        return pieces;
    }

    private int chooseSplitIndex(String text, int start) {
        int max = Math.min(text.length(), start + MAX_SPLIT_CHARS);
        int target = Math.min(text.length(), start + TARGET_CHARS);
        for (int i = target; i >= start + MIN_SPLIT_CHARS; i--) {
            if (i < text.length() && isBoundary(text.charAt(i))) {
                return i;
            }
        }
        return max;
    }

    private boolean isBoundary(char ch) {
        return Character.isWhitespace(ch) || ".,;:!?，。；：！？".indexOf(ch) >= 0;
    }

    private DocsChunk buildChunk(String docPath, String headingPath, ChunkPart part, int sectionContentOffset) {
        int absoluteStart = sectionContentOffset + part.startOffset();
        String content = part.text().trim();
        String source = docPath + "|" + headingPath + "|" + absoluteStart;
        String chunkId = stableHash(source);
        int tokenEstimate = Math.max(1, content.length() / 2);
        return new DocsChunk(chunkId, docPath, headingPath, content, tokenEstimate);
    }

    private String stableHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record ChunkPart(int startOffset, int endOffset, String text) {}
}
