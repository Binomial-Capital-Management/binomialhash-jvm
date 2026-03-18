package com.binomialtechnologies.binomialhash.middleware;

import com.binomialtechnologies.binomialhash.context.BinomialHashContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Middleware for automatic BinomialHash interception of large tool outputs.
 *
 * <p>When a tool returns a large structured payload (Map/List whose JSON
 * serialisation exceeds {@code threshold} characters), the middleware ingests
 * it into a BinomialHash instance and returns the compact summary. Small or
 * non-structured outputs pass through unchanged.</p>
 *
 * <p>A ThreadLocal-based <b>raw mode</b> lets callers bypass interception
 * when they need the native payload.</p>
 */
public final class BinomialHashMiddleware {

    private static final Logger LOG = Logger.getLogger(BinomialHashMiddleware.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_THRESHOLD = 3000;

    private BinomialHashMiddleware() {}

    /**
     * Wrap a function so its return value is auto-ingested when large.
     *
     * @param fn        the original tool function
     * @param label     BH storage key label used during ingest
     * @param bh        explicit BinomialHash instance ({@code null} to use context)
     * @param threshold minimum character count for ingestion to kick in
     * @param <T>       input type
     * @param <R>       return type
     * @return wrapped function
     */
    @SuppressWarnings("unchecked")
    public static <T, R> Function<T, R> wrapToolWithBh(
            Function<T, R> fn, String label, Object bh, int threshold) {
        return input -> {
            R result = fn.apply(input);
            return (R) maybeIngest(result, label, bh, threshold);
        };
    }

    public static <T, R> Function<T, R> wrapToolWithBh(Function<T, R> fn, String label) {
        return wrapToolWithBh(fn, label, null, DEFAULT_THRESHOLD);
    }

    /**
     * Inspect a result and ingest into BH if it is large structured data.
     *
     * @param result    the tool's return value
     * @param label     BH storage key label
     * @param bh        explicit BinomialHash instance ({@code null} to use context)
     * @param threshold minimum character count for ingestion
     * @return the compact summary if ingested, otherwise the original result
     */
    public static Object maybeIngest(Object result, String label, Object bh, int threshold) {
        if (BinomialHashContext.isRawMode()) {
            return result;
        }

        String serialised = serialiseIfLarge(result, threshold);
        if (serialised == null) {
            return result;
        }

        Object instance = bh != null ? bh : BinomialHashContext.getBinomialHash();
        try {
            Method ingestMethod = instance.getClass().getMethod("ingest", String.class, String.class);
            Object summary = ingestMethod.invoke(instance, serialised, label);
            LOG.fine("[BH-middleware] ingested " + serialised.length() + " chars under label '" + label + "'");
            return summary;
        } catch (Exception e) {
            LOG.warning("[BH-middleware] ingest failed for label '" + label + "', passing through: " + e.getMessage());
            return result;
        }
    }

    /**
     * Serialise the result to JSON if it's a Map/Collection/String exceeding the threshold.
     *
     * @return the serialised JSON string if large, or {@code null} if not eligible
     */
    private static String serialiseIfLarge(Object result, int threshold) {
        String serialised;
        if (result instanceof Map || result instanceof Collection) {
            try {
                serialised = MAPPER.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                return null;
            }
        } else if (result instanceof String) {
            serialised = (String) result;
        } else {
            return null;
        }

        if (serialised.length() <= threshold) {
            return null;
        }
        return serialised;
    }
}
