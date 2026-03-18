package com.binomialtechnologies.binomialhash.tokenizers;

/**
 * Minimal interface every provider tokenizer must satisfy.
 */
public interface TokenCounter {

    /**
     * Count (or estimate) tokens for the given text.
     *
     * @param text the string to measure
     * @return token count
     */
    int countTokens(String text);

    /**
     * Return {@code true} if this counter provides exact token counts
     * (as opposed to the chars/4 heuristic).
     */
    boolean isExact();
}
