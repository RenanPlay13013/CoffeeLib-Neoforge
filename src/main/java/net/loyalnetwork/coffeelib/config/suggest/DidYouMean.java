package net.loyalnetwork.coffeelib.config.suggest;

import java.util.Collection;
import java.util.Optional;

/**
 * Approximate match suggestion based on Levenshtein distance.
 * Used to warn about mistyped config keys.
 */
public final class DidYouMean {

    private DidYouMean() {
    }

    /** Uses an edit threshold scaled by the length of the input text. */
    public static Optional<String> suggest(String input, Collection<String> candidates) {
        return suggest(input, candidates, defaultThreshold(input));
    }

    public static Optional<String> suggest(String input, Collection<String> candidates, int maxDistance) {
        if (input == null || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }

        String best = null;
        int bestDistance = Integer.MAX_VALUE;

        for (String candidate : candidates) {
            if (candidate.equals(input)) {
                continue; // equal is not a "suggestion"
            }
            int distance = levenshtein(input, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        if (best != null && bestDistance <= maxDistance) {
            return Optional.of(best);
        }
        return Optional.empty();
    }

    private static int defaultThreshold(String input) {
        int len = input.length();
        if (len <= 3) return 1;
        if (len <= 6) return 2;
        return 3;
    }

    /** Classic edit distance, O(n*m) time, O(m) memory (two rows). */
    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }

        return prev[b.length()];
    }
}