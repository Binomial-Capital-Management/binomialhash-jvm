package com.binomialtechnologies.binomialhash.adapters;

import com.binomialtechnologies.binomialhash.tools.ToolSpec;

import java.util.*;

/**
 * OpenAI adapter — Responses API and Chat Completions API.
 *
 * <p>Translates {@link ToolSpec} objects into the map shapes that the
 * OpenAI SDK expects in its {@code tools} parameter.</p>
 *
 * <p>Two formats are supported:
 * <ul>
 *   <li><b>Responses API</b> (recommended): top-level type/name/description/parameters</li>
 *   <li><b>Chat Completions API</b> (legacy): nested under function: {name, description, parameters}</li>
 * </ul></p>
 *
 * <p>Wire format (Responses API):
 * <pre>{@code
 * {
 *   "type": "function",
 *   "name": "bh_retrieve",
 *   "description": "...",
 *   "parameters": { "type": "object", "properties": {...}, "required": [...] }
 * }
 * }</pre></p>
 */
public final class OpenAiAdapter {

    private OpenAiAdapter() {}

    /**
     * Return tool definitions for the OpenAI Responses API (default) or Chat Completions API.
     *
     * @param specs  ToolSpec objects
     * @param strict if true, enable Structured Outputs constraints
     * @param format "responses" (default) or "chat_completions"
     * @return list of tool definition maps
     */
    public static List<Map<String, Object>> getOpenAiTools(List<ToolSpec> specs, boolean strict, String format) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpec spec : specs) {
            if ("chat_completions".equals(format)) {
                tools.add(specToChatCompletions(spec, strict));
            } else {
                tools.add(specToResponses(spec, strict));
            }
        }
        return tools;
    }

    public static List<Map<String, Object>> getOpenAiTools(List<ToolSpec> specs) {
        return getOpenAiTools(specs, false, "responses");
    }

    /**
     * Execute a tool call from an OpenAI response.
     *
     * @param specs     the same specs used to generate the tool list
     * @param name      function_call.name from the model output
     * @param arguments function_call.arguments — JSON string or map
     * @return handler result
     */
    public static Object handleOpenAiToolCall(List<ToolSpec> specs, String name, Object arguments) {
        return AdapterCommon.handleToolCall(specs, name, AdapterCommon.parseArguments(arguments));
    }

    private static Map<String, Object> specToResponses(ToolSpec spec, boolean strict) {
        Map<String, Object> params = deepCopySchema(spec.getInputSchema());
        if (strict) {
            applyStrictMode(params);
        }

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("name", spec.getName());
        tool.put("description", spec.getDescription());
        tool.put("parameters", params);
        if (strict) {
            tool.put("strict", true);
        }
        return tool;
    }

    private static Map<String, Object> specToChatCompletions(ToolSpec spec, boolean strict) {
        Map<String, Object> params = deepCopySchema(spec.getInputSchema());
        if (strict) {
            applyStrictMode(params);
        }

        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", spec.getName());
        fn.put("description", spec.getDescription());
        fn.put("parameters", params);
        if (strict) {
            fn.put("strict", true);
        }

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", fn);
        return tool;
    }

    /**
     * Enforce OpenAI Structured Outputs constraints in-place.
     * additionalProperties must be false; all properties must be in required.
     */
    @SuppressWarnings("unchecked")
    private static void applyStrictMode(Map<String, Object> params) {
        params.putIfAbsent("additionalProperties", false);
        Object props = params.get("properties");
        if (props instanceof Map) {
            params.put("required", new ArrayList<>(((Map<String, Object>) props).keySet()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopySchema(Map<String, Object> original) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Map) {
                copy.put(entry.getKey(), deepCopySchema((Map<String, Object>) val));
            } else if (val instanceof List) {
                copy.put(entry.getKey(), new ArrayList<>((List<?>) val));
            } else {
                copy.put(entry.getKey(), val);
            }
        }
        return copy;
    }
}
