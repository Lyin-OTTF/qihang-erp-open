package cn.qihangerp.agent.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic text generation metrics used by RAG evaluation.
 */
public final class TextGenerationMetrics {

    private TextGenerationMetrics() {
    }

    public static double bleu4(String candidate, String reference) {
        List<String> candidateTokens = tokenize(candidate);
        List<String> referenceTokens = tokenize(reference);
        if (candidateTokens.isEmpty() || referenceTokens.isEmpty()) {
            return 0.0;
        }

        double logPrecisionSum = 0.0;
        for (int order = 1; order <= 4; order++) {
            Map<List<String>, Integer> referenceCounts = ngramCounts(referenceTokens, order);
            Map<List<String>, Integer> candidateCounts = ngramCounts(candidateTokens, order);
            if (candidateCounts.isEmpty()) {
                return 0.0;
            }

            int matches = 0;
            for (Map.Entry<List<String>, Integer> entry : candidateCounts.entrySet()) {
                int referenceCount = referenceCounts.getOrDefault(entry.getKey(), 0);
                matches += Math.min(entry.getValue(), referenceCount);
            }
            if (matches == 0) {
                return 0.0;
            }
            logPrecisionSum += Math.log((double) matches / candidateCounts.size()) / 4.0;
        }

        double brevityPenalty = candidateTokens.size() >= referenceTokens.size()
                ? 1.0
                : Math.exp(1.0 - (double) referenceTokens.size() / candidateTokens.size());
        return brevityPenalty * Math.exp(logPrecisionSum);
    }

    public static double rougeL(String candidate, String reference) {
        List<String> candidateTokens = tokenize(candidate);
        List<String> referenceTokens = tokenize(reference);
        if (candidateTokens.isEmpty() || referenceTokens.isEmpty()) {
            return 0.0;
        }

        int lcs = longestCommonSubsequence(candidateTokens, referenceTokens);
        if (lcs == 0) {
            return 0.0;
        }
        double precision = (double) lcs / candidateTokens.size();
        double recall = (double) lcs / referenceTokens.size();
        return precision + recall == 0.0
                ? 0.0
                : 2.0 * precision * recall / (precision + recall);
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }

        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (isCjk(codePoint)) {
                tokens.add(new String(Character.toChars(codePoint)).toLowerCase());
                index += Character.charCount(codePoint);
                continue;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                int start = index;
                index += Character.charCount(codePoint);
                while (index < text.length()) {
                    int next = text.codePointAt(index);
                    if (isCjk(next) || !Character.isLetterOrDigit(next)) {
                        break;
                    }
                    index += Character.charCount(next);
                }
                tokens.add(text.substring(start, index).toLowerCase());
                continue;
            }
            index += Character.charCount(codePoint);
        }
        return tokens;
    }

    private static Map<List<String>, Integer> ngramCounts(List<String> tokens, int order) {
        Map<List<String>, Integer> counts = new HashMap<>();
        for (int i = 0; i <= tokens.size() - order; i++) {
            List<String> ngram = List.copyOf(tokens.subList(i, i + order));
            counts.merge(ngram, 1, Integer::sum);
        }
        return counts;
    }

    private static int longestCommonSubsequence(List<String> left, List<String> right) {
        int[] previous = new int[right.size() + 1];
        int[] current = new int[right.size() + 1];
        for (String leftToken : left) {
            for (int j = 1; j <= right.size(); j++) {
                if (leftToken.equals(right.get(j - 1))) {
                    current[j] = previous[j - 1] + 1;
                } else {
                    current[j] = Math.max(previous[j], current[j - 1]);
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
            java.util.Arrays.fill(current, 0);
        }
        return previous[right.size()];
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4e00 && codePoint <= 0x9fff)
                || (codePoint >= 0x3400 && codePoint <= 0x4dbf);
    }
}
