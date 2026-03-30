package com.mingming.agent.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class Bm25RetrieverService {

    private static final double K1 = 1.2D;
    private static final double B = 0.75D;

    public List<RetrievalHit> search(String query, List<DocsChunk> chunks, int topK, double threshold) {
        if (query == null || query.isBlank() || chunks == null || chunks.isEmpty() || topK <= 0) {
            return List.of();
        }

        Map<String, Integer> queryTermFrequency = termFrequency(tokenize(query));
        if (queryTermFrequency.isEmpty()) {
            return List.of();
        }

        List<DocumentStats> docs = new ArrayList<>(chunks.size());
        Map<String, Integer> documentFrequency = new HashMap<>();
        int totalLength = 0;

        for (DocsChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            List<String> tokens = tokenize(buildDocumentText(chunk));
            Map<String, Integer> frequencies = termFrequency(tokens);
            totalLength += tokens.size();

            Set<String> uniqueTerms = new HashSet<>(frequencies.keySet());
            for (String term : uniqueTerms) {
                documentFrequency.merge(term, 1, Integer::sum);
            }

            docs.add(new DocumentStats(chunk, frequencies, tokens.size()));
        }

        if (docs.isEmpty()) {
            return List.of();
        }

        double averageDocLength = Math.max(1.0D, (double) totalLength / docs.size());
        List<RetrievalHit> scored = new ArrayList<>(docs.size());

        for (DocumentStats doc : docs) {
            double score = scoreDocument(doc, queryTermFrequency, documentFrequency, docs.size(), averageDocLength);
            if (score > 0.0D && score >= threshold) {
                scored.add(new RetrievalHit(doc.chunk(), score));
            }
        }

        scored.sort((left, right) -> Double.compare(right.score(), left.score()));
        return scored.stream().limit(topK).toList();
    }

    public List<DocsChunk> retrieve(String query, List<DocsChunk> chunks, int topK, double threshold) {
        return search(query, chunks, topK, threshold).stream().map(RetrievalHit::chunk).toList();
    }

    private double scoreDocument(
            DocumentStats doc,
            Map<String, Integer> queryTermFrequency,
            Map<String, Integer> documentFrequency,
            int totalDocs,
            double averageDocLength) {
        if (doc.length() == 0) {
            return 0.0D;
        }

        double score = 0.0D;
        for (Map.Entry<String, Integer> entry : queryTermFrequency.entrySet()) {
            String term = entry.getKey();
            int termFrequency = doc.termFrequency().getOrDefault(term, 0);
            if (termFrequency == 0) {
                continue;
            }

            int docsWithTerm = documentFrequency.getOrDefault(term, 0);
            if (docsWithTerm == 0) {
                continue;
            }

            double idf = Math.log(1.0D + (totalDocs - docsWithTerm + 0.5D) / (docsWithTerm + 0.5D));
            double denominator = termFrequency + K1 * (1.0D - B + B * doc.length() / averageDocLength);
            double tfWeight = (termFrequency * (K1 + 1.0D)) / denominator;
            score += idf * tfWeight * entry.getValue();
        }
        return score;
    }

    private String buildDocumentText(DocsChunk chunk) {
        return chunk.headingPath() + "\n" + chunk.content();
    }

    private Map<String, Integer> termFrequency(List<String> tokens) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (String token : tokens) {
            frequencies.merge(token, 1, Integer::sum);
        }
        return frequencies;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder latinToken = new StringBuilder();
        List<String> hanRun = new ArrayList<>();

        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            if (isAsciiLetterOrDigit(codePoint)) {
                flushHanRun(tokens, hanRun);
                latinToken.appendCodePoint(codePoint);
            } else if (isChineseCodePoint(codePoint)) {
                flushLatinToken(tokens, latinToken);
                hanRun.add(new String(Character.toChars(codePoint)));
            } else {
                flushLatinToken(tokens, latinToken);
                flushHanRun(tokens, hanRun);
            }
            i += Character.charCount(codePoint);
        }
        flushLatinToken(tokens, latinToken);
        flushHanRun(tokens, hanRun);
        return tokens;
    }

    private boolean isAsciiLetterOrDigit(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= '0' && codePoint <= '9');
    }

    private boolean isChineseCodePoint(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN;
    }

    private void flushLatinToken(List<String> out, StringBuilder latinToken) {
        if (latinToken.isEmpty()) {
            return;
        }
        out.add(latinToken.toString());
        latinToken.setLength(0);
    }

    private void flushHanRun(List<String> out, List<String> hanRun) {
        if (hanRun.isEmpty()) {
            return;
        }
        if (hanRun.size() == 1) {
            out.add(hanRun.get(0));
            hanRun.clear();
            return;
        }

        for (int i = 0; i < hanRun.size() - 1; i++) {
            out.add(hanRun.get(i) + hanRun.get(i + 1));
        }
        hanRun.clear();
    }

    private record DocumentStats(DocsChunk chunk, Map<String, Integer> termFrequency, int length) {}

    public record RetrievalHit(DocsChunk chunk, double score) {}
}
