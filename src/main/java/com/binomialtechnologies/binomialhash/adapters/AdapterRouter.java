package com.binomialtechnologies.binomialhash.adapters;

import com.binomialtechnologies.binomialhash.tools.ToolSpec;

import java.util.*;
import java.util.function.Function;

/**
 * Convenience router for dynamic provider selection.
 *
 * <pre>{@code
 * List<Map<String, Object>> tools = AdapterRouter.getToolsForProvider(specs, "openai");
 * }</pre>
 */
public final class AdapterRouter {

    private static final Map<String, Function<List<ToolSpec>, List<Map<String, Object>>>> PROVIDERS = new LinkedHashMap<>();

    static {
        PROVIDERS.put("openai", OpenAiAdapter::getOpenAiTools);
        PROVIDERS.put("anthropic", AnthropicAdapter::getAnthropicTools);
        PROVIDERS.put("gemini", GeminiAdapter::getGeminiTools);
        PROVIDERS.put("xai", XaiAdapter::getXaiTools);
    }

    private AdapterRouter() {}

    /**
     * Return tool definitions formatted for the given provider.
     *
     * @param specs    ToolSpec objects
     * @param provider one of "openai", "anthropic", "gemini", "xai"
     * @return list of tool definition maps in the provider's wire format
     * @throws IllegalArgumentException if provider is not recognised
     */
    public static List<Map<String, Object>> getToolsForProvider(List<ToolSpec> specs, String provider) {
        Function<List<ToolSpec>, List<Map<String, Object>>> fn = PROVIDERS.get(provider);
        if (fn == null) {
            throw new IllegalArgumentException(
                    "Unknown provider '" + provider + "'. Choose from: " + PROVIDERS.keySet());
        }
        return fn.apply(specs);
    }
}
