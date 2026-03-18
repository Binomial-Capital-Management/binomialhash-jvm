package com.binomialtechnologies.binomialhash.tokenizers;

import java.util.logging.Logger;

/**
 * OpenAI token counting via JTokkit.
 *
 * <p>Uses the {@code o200k_base} encoding (GPT-4o / GPT-4.1 / GPT-5 family).
 * Falls back to the character heuristic if JTokkit is not on the classpath.</p>
 *
 * <p>Install exact counting by adding JTokkit to your dependencies:
 * <pre>{@code implementation("com.knuddels:jtokkit:1.1.0")}</pre></p>
 */
public class OpenAiTokenCounter implements TokenCounter {

    private static final Logger LOG = Logger.getLogger(OpenAiTokenCounter.class.getName());
    private static final String DEFAULT_ENCODING = "o200k_base";

    private final boolean jtokkitAvailable;
    private final Object encoding; // com.knuddels.jtokkit.api.Encoding — kept as Object to avoid hard dep
    private final FallbackCounter fallback;

    public OpenAiTokenCounter() {
        this(DEFAULT_ENCODING);
    }

    public OpenAiTokenCounter(String encodingName) {
        Object enc = null;
        boolean available = false;
        try {
            Class<?> registryClass = Class.forName("com.knuddels.jtokkit.Encodings");
            Object registry = registryClass.getMethod("newDefaultEncodingRegistry").invoke(null);

            Class<?> encodingTypeClass = Class.forName("com.knuddels.jtokkit.api.EncodingType");
            Object encodingType = null;
            for (Object constant : encodingTypeClass.getEnumConstants()) {
                if (constant.toString().equalsIgnoreCase(encodingName.replace("-", "_"))) {
                    encodingType = constant;
                    break;
                }
            }
            if (encodingType != null) {
                enc = registry.getClass().getMethod("getEncoding", encodingTypeClass).invoke(registry, encodingType);
                available = true;
            }
        } catch (Exception e) {
            LOG.fine("JTokkit not available: " + e.getMessage());
        }
        this.encoding = enc;
        this.jtokkitAvailable = available;
        this.fallback = new FallbackCounter("openai");
    }

    @Override
    public int countTokens(String text) {
        if (jtokkitAvailable && encoding != null) {
            try {
                Object result = encoding.getClass().getMethod("encode", String.class).invoke(encoding, text);
                return ((java.util.List<?>) result).size();
            } catch (Exception e) {
                LOG.warning("JTokkit encode failed, falling back: " + e.getMessage());
            }
        }
        return fallback.countTokens(text);
    }

    @Override
    public boolean isExact() {
        return jtokkitAvailable;
    }
}
