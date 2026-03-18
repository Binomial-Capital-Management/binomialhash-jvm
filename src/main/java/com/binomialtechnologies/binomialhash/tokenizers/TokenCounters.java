package com.binomialtechnologies.binomialhash.tokenizers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider-aware token counting facade.
 *
 * <p>Provides a single entry point for estimating how many tokens a piece
 * of text will consume under a given provider's tokenizer.</p>
 *
 * <p>Exact counting requires optional dependencies:
 * <ul>
 *   <li><b>openai / xai</b>: add {@code com.knuddels:jtokkit} to the classpath</li>
 *   <li><b>anthropic / gemini</b>: no offline tokenizer available (heuristic only)</li>
 * </ul></p>
 */
public final class TokenCounters {

    private static final Map<String, TokenCounter> COUNTERS = new ConcurrentHashMap<>();

    static {
        OpenAiTokenCounter openai = new OpenAiTokenCounter();
        COUNTERS.put("openai", openai);
        COUNTERS.put("xai", openai); // xAI uses OpenAI-compatible tokenizer
        COUNTERS.put("anthropic", new FallbackCounter("anthropic"));
        COUNTERS.put("gemini", new FallbackCounter("gemini"));
    }

    private TokenCounters() {}

    /**
     * Count (or estimate) tokens for {@code text} under {@code provider}'s tokenizer.
     *
     * @param text     the string to measure
     * @param provider one of "openai", "anthropic", "gemini", "xai"
     * @return token count (exact when the provider library is available, otherwise ceil(chars/4))
     */
    public static int countTokens(String text, String provider) {
        TokenCounter counter = COUNTERS.get(provider);
        if (counter == null) {
            throw new IllegalArgumentException(
                    "Unknown provider '" + provider + "'. Choose from: " + COUNTERS.keySet());
        }
        return counter.countTokens(text);
    }

    /** Count tokens using the OpenAI tokenizer (default). */
    public static int countTokens(String text) {
        return countTokens(text, "openai");
    }

    /**
     * Return {@code true} if {@code provider} has an exact offline tokenizer loaded.
     */
    public static boolean isExact(String provider) {
        TokenCounter counter = COUNTERS.get(provider);
        return counter != null && counter.isExact();
    }

    /** Check OpenAI exact support (default). */
    public static boolean isExact() {
        return isExact("openai");
    }
}
