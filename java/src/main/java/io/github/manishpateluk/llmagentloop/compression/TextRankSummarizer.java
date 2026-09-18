package io.github.manishpateluk.llmagentloop.compression;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A small, dependency-free implementation of TextRank sentence extraction (Mihalcea &amp;
 * Tarau, 2004): sentences become nodes in a graph, edges are weighted by word overlap, and a
 * PageRank-style power iteration ranks each sentence's importance. The highest-ranked sentences
 * are returned in their original order, so the summary still reads coherently.
 *
 * <p>No ML model is loaded — this is pure text processing, so it's cheap enough to run on every
 * compression attempt regardless of hardware.
 */
public final class TextRankSummarizer {

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9\"'(])");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9']+");
    private static final int MAX_ITERATIONS = 30;
    private static final double DAMPING = 0.85;
    private static final double CONVERGENCE_THRESHOLD = 1e-4;

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "so", "of", "to", "in", "on",
            "at", "for", "with", "as", "by", "is", "are", "was", "were", "be", "been", "being",
            "it", "its", "this", "that", "these", "those", "i", "you", "he", "she", "we", "they",
            "them", "his", "her", "our", "your", "their", "not", "no", "do", "does", "did",
            "can", "could", "will", "would", "should", "may", "might", "have", "has", "had",
            "from", "into", "about", "than", "which", "who", "what", "when", "where", "how");

    private TextRankSummarizer() {
    }

    /**
     * @param text          the text to summarize
     * @param keepFraction  fraction of sentences to retain, in (0, 1]
     * @return the highest-ranked sentences, in original order, joined with a single space
     */
    public static String summarize(String text, double keepFraction) {
        if (text == null || text.isBlank()) {
            return text;
        }
        List<String> sentences = splitSentences(text);
        int keep = Math.max(1, (int) Math.ceil(sentences.size() * keepFraction));
        if (sentences.size() <= keep) {
            return text;
        }

        List<Set<String>> wordSets = sentences.stream().map(TextRankSummarizer::wordsOf).toList();
        double[][] similarity = buildSimilarityMatrix(wordSets);
        double[] scores = rank(similarity);

        List<Integer> rankedIndices = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            rankedIndices.add(i);
        }
        rankedIndices.sort((a, b) -> Double.compare(scores[b], scores[a]));

        List<Integer> selected = new ArrayList<>(rankedIndices.subList(0, keep));
        selected.sort(Integer::compareTo);

        StringBuilder summary = new StringBuilder();
        for (int index : selected) {
            if (!summary.isEmpty()) {
                summary.append(' ');
            }
            summary.append(sentences.get(index));
        }
        return summary.toString();
    }

    static List<String> splitSentences(String text) {
        String[] parts = SENTENCE_BOUNDARY.split(text.strip());
        List<String> sentences = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    private static Set<String> wordsOf(String sentence) {
        Set<String> words = new HashSet<>();
        var matcher = WORD.matcher(sentence.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (!STOPWORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    /** Edge weight is the classic TextRank overlap measure: shared words normalized by the sum of log lengths. */
    private static double[][] buildSimilarityMatrix(List<Set<String>> wordSets) {
        int n = wordSets.size();
        double[][] matrix = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double weight = overlapSimilarity(wordSets.get(i), wordSets.get(j));
                matrix[i][j] = weight;
                matrix[j][i] = weight;
            }
        }
        return matrix;
    }

    private static double overlapSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        long shared = a.stream().filter(b::contains).count();
        if (shared == 0) {
            return 0.0;
        }
        double normalizer = Math.log(a.size() + 1.0) + Math.log(b.size() + 1.0);
        return normalizer == 0.0 ? 0.0 : shared / normalizer;
    }

    private static double[] rank(double[][] similarity) {
        int n = similarity.length;
        double[] outWeightSum = new double[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                outWeightSum[i] += similarity[i][j];
            }
        }

        double[] scores = new double[n];
        java.util.Arrays.fill(scores, 1.0 / n);

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            double[] next = new double[n];
            double delta = 0.0;
            for (int i = 0; i < n; i++) {
                double incoming = 0.0;
                for (int j = 0; j < n; j++) {
                    if (i == j || outWeightSum[j] == 0.0) {
                        continue;
                    }
                    incoming += (similarity[j][i] / outWeightSum[j]) * scores[j];
                }
                next[i] = (1 - DAMPING) + DAMPING * incoming;
                delta += Math.abs(next[i] - scores[i]);
            }
            scores = next;
            if (delta < CONVERGENCE_THRESHOLD) {
                break;
            }
        }
        return scores;
    }
}
