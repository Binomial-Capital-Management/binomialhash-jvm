package com.binomialtechnologies.binomialhash.adapters;

import com.binomialtechnologies.binomialhash.tools.ToolSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.logging.Logger;

/**
 * Shared handler dispatch used by every provider adapter.
 *
 * <p>The core job: given a tool name and an arguments map, find the matching
 * ToolSpec and invoke its handler. Provider-specific adapters normalise
 * their incoming payloads (JSON-string arguments, protobuf args, etc.)
 * into a plain {@code Map} before calling {@link #handleToolCall}.</p>
 */
public final class AdapterCommon {

    private static final Logger LOG = Logger.getLogger(AdapterCommon.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdapterCommon() {}

    /**
     * Dispatch {@code name} to the correct handler in {@code specs}.
     *
     * @param specs     the full set of ToolSpec objects
     * @param name      tool name the model chose to call
     * @param arguments already-parsed keyword arguments
     * @return whatever the handler returns
     * @throws IllegalArgumentException if name does not match any spec
     */
    public static Object handleToolCall(List<ToolSpec> specs, String name, Map<String, Object> arguments) {
        Map<String, ToolSpec> index = buildIndex(specs);
        ToolSpec spec = index.get(name);
        if (spec == null) {
            throw new IllegalArgumentException(
                    "Unknown tool '" + name + "'. Available: " + new TreeSet<>(index.keySet()));
        }
        return spec.getHandler().apply(arguments);
    }

    /**
     * Like {@link #handleToolCall} but catches exceptions.
     *
     * @return map with "result" on success or "error" on failure
     */
    public static Map<String, Object> safeHandleToolCall(List<ToolSpec> specs, String name, Map<String, Object> arguments) {
        try {
            Object result = handleToolCall(specs, name, arguments);
            return Map.of("result", result);
        } catch (Exception e) {
            LOG.warning("Tool '" + name + "' raised " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Normalise raw arguments into a plain map.
     *
     * <p>Handles three shapes:
     * <ul>
     *   <li>Already a Map → returned as-is</li>
     *   <li>A JSON string (OpenAI/xAI function_call.arguments) → decoded</li>
     *   <li>null or empty → returns empty map</li>
     * </ul></p>
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseArguments(Object raw) {
        if (raw instanceof Map) {
            return (Map<String, Object>) raw;
        }
        if (raw instanceof String) {
            try {
                Object parsed = MAPPER.readValue((String) raw, Map.class);
                if (parsed instanceof Map) {
                    return (Map<String, Object>) parsed;
                }
            } catch (JsonProcessingException e) {
                // fall through
            }
        }
        return Collections.emptyMap();
    }

    private static Map<String, ToolSpec> buildIndex(List<ToolSpec> specs) {
        Map<String, ToolSpec> idx = new HashMap<>();
        for (ToolSpec s : specs) {
            idx.put(s.getName(), s);
        }
        return idx;
    }
}
