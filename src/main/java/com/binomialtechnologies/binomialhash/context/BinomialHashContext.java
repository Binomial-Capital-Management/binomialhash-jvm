package com.binomialtechnologies.binomialhash.context;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Request-scoped convenience helpers for BinomialHash instances.
 *
 * <p>Java equivalent of Python's {@code contextvars.ContextVar}. Uses
 * {@code ThreadLocal} for per-thread isolation (Java's equivalent of
 * Python's context-local storage).</p>
 *
 * <p>In async frameworks (e.g., virtual threads, reactive), consider
 * using scoped values or passing the instance explicitly.</p>
 */
public final class BinomialHashContext {

    private static final Logger LOG = Logger.getLogger(BinomialHashContext.class.getName());

    private static final ThreadLocal<Object> BH_INSTANCE = new ThreadLocal<>();

    /**
     * Depth counter (not a boolean) so nested raw-mode contexts work correctly.
     */
    private static final ThreadLocal<AtomicInteger> RAW_MODE_DEPTH =
            ThreadLocal.withInitial(() -> new AtomicInteger(0));

    private static Supplier<Object> bhFactory;

    private BinomialHashContext() {}

    /**
     * Set the factory used to create new BinomialHash instances.
     * Must be called once at application startup before any other methods.
     *
     * @param factory supplier that creates a new BinomialHash
     */
    public static void setBhFactory(Supplier<Object> factory) {
        bhFactory = factory;
    }

    /**
     * Initialize a fresh BinomialHash for the current thread/request.
     *
     * @return the new instance
     */
    public static Object initBinomialHash() {
        if (bhFactory == null) {
            throw new IllegalStateException(
                    "BinomialHashContext.setBhFactory() must be called before initBinomialHash()");
        }
        Object bh = bhFactory.get();
        BH_INSTANCE.set(bh);
        LOG.info("[BH] initialized new instance for request");
        return bh;
    }

    /**
     * Get the current thread's BinomialHash (auto-init if needed).
     */
    public static Object getBinomialHash() {
        Object bh = BH_INSTANCE.get();
        if (bh == null) {
            bh = initBinomialHash();
        }
        return bh;
    }

    /**
     * Clear the current thread's BinomialHash instance.
     * Call at the end of a request to prevent leaks.
     */
    public static void clear() {
        BH_INSTANCE.remove();
        RAW_MODE_DEPTH.remove();
    }

    /**
     * Enter raw mode — bypass BH compaction.
     * Must be paired with {@link #exitRawMode()}.
     */
    public static void enterRawMode() {
        RAW_MODE_DEPTH.get().incrementAndGet();
    }

    /**
     * Exit raw mode — restore previous compaction state.
     */
    public static void exitRawMode() {
        AtomicInteger depth = RAW_MODE_DEPTH.get();
        if (depth.get() > 0) {
            depth.decrementAndGet();
        }
    }

    /**
     * Return {@code true} when inside a raw-mode context.
     */
    public static boolean isRawMode() {
        return RAW_MODE_DEPTH.get().get() > 0;
    }

    /**
     * Execute a runnable within raw mode, restoring on exit.
     */
    public static void withRawMode(Runnable action) {
        enterRawMode();
        try {
            action.run();
        } finally {
            exitRawMode();
        }
    }

    /**
     * Execute a supplier within raw mode, restoring on exit.
     */
    public static <T> T withRawMode(Supplier<T> action) {
        enterRawMode();
        try {
            return action.get();
        } finally {
            exitRawMode();
        }
    }
}
