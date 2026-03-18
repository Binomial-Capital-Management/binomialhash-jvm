package com.binomialtechnologies.binomialhash.tokenizers;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Token counter that uses the character heuristic ({@code ceil(len / 4)}).
 *
 * <p>Emits a one-time warning per provider name so users know exact
 * counting is not active.</p>
 */
public class FallbackCounter implements TokenCounter {

    private static final Logger LOG = Logger.getLogger(FallbackCounter.class.getName());

    /** ~4 chars/token is a rough average across English prose and JSON. */
    public static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    /** Class-level set acts as a singleton dedup so each provider warns only once per process. */
    private static final Set<String> WARNED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final String provider;

    public FallbackCounter(String provider) {
        this.provider = provider;
    }

    public FallbackCounter() {
        this("unknown");
    }

    @Override
    public int countTokens(String text) {
        if (WARNED.add(provider)) {
            LOG.warning(String.format(
                    "[BH-tokenizer] No native tokenizer for '%s'; "
                    + "using chars/4 heuristic. Add jtokkit to the classpath for exact counts.",
                    provider));
        }
        return charsFallback(text);
    }

    @Override
    public boolean isExact() {
        return false;
    }

    /**
     * Estimate token count from character length ({@code ceil(len / 4)}).
     *
     * <p>This is a coarse heuristic — roughly correct for English prose and
     * JSON, but can be off by 20-30% on code or non-Latin scripts.</p>
     */
    public static int charsFallback(String text) {
        return (text.length() + CHARS_PER_TOKEN_ESTIMATE - 1) / CHARS_PER_TOKEN_ESTIMATE;
    }
}
