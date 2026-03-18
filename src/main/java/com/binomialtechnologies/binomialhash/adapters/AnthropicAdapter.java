package com.binomialtechnologies.binomialhash.adapters;

import com.binomialtechnologies.binomialhash.tools.ToolSpec;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Anthropic adapter — Claude Messages API.
 *
 * <p>Translates {@link ToolSpec} objects into the map shapes expected by
 * the Anthropic SDK's {@code tools} parameter.</p>
 *
 * <p>Wire format:
 * <pre>{@code
 * {
 *   "name": "bh_retrieve",           // must match ^[a-zA-Z0-9_-]{1,64}$
 *   "description": "...",
 *   "input_schema": { "type": "object", "properties": {...}, "required": [...] }
 * }
 * }</pre></p>
 */
public final class AnthropicAdapter {

    private static final Pattern NAME_RE = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private AnthropicAdapter() {}

    /**
     * Return tool definitions for the Anthropic Messages API.
     *
     * @param specs    ToolSpec objects
     * @param examples optional mapping of tool_name → list of example input maps
     * @return list of tool definition maps
     */
    public static List<Map<String, Object>> getAnthropicTools(
            List<ToolSpec> specs, Map<String, List<Map<String, Object>>> examples) {
        if (examples == null) examples = Collections.emptyMap();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpec spec : specs) {
            tools.add(specToAnthropic(spec, examples.get(spec.getName())));
        }
        return tools;
    }

    public static List<Map<String, Object>> getAnthropicTools(List<ToolSpec> specs) {
        return getAnthropicTools(specs, null);
    }

    /**
     * Execute a tool call from an Anthropic tool_use content block.
     *
     * @param specs     the same specs used to generate the tool list
     * @param name      content_block.name
     * @param toolInput content_block.input — Anthropic delivers as a parsed map
     * @return handler result
     */
    public static Object handleAnthropicToolUse(List<ToolSpec> specs, String name, Map<String, Object> toolInput) {
        if (toolInput == null) toolInput = Collections.emptyMap();
        return AdapterCommon.handleToolCall(specs, name, toolInput);
    }

    private static Map<String, Object> specToAnthropic(ToolSpec spec, List<Map<String, Object>> examples) {
        validateName(spec.getName());

        String description = spec.getDescription();
        if (examples != null && !examples.isEmpty()) {
            StringBuilder sb = new StringBuilder(description);
            sb.append("\n\nExample inputs:\n");
            for (Map<String, Object> ex : examples) {
                sb.append("  {");
                StringJoiner joiner = new StringJoiner(", ");
                for (Map.Entry<String, Object> e : ex.entrySet()) {
                    joiner.add("\"" + e.getKey() + "\": " + toJsonValue(e.getValue()));
                }
                sb.append(joiner).append("}\n");
            }
            description = sb.toString();
        }

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", spec.getName());
        tool.put("description", description);
        tool.put("input_schema", new LinkedHashMap<>(spec.getInputSchema()));
        return tool;
    }

    private static void validateName(String name) {
        if (!NAME_RE.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Tool name '" + name + "' does not match Anthropic's required pattern: ^[a-zA-Z0-9_-]{1,64}$");
        }
    }

    private static String toJsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + value + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "\"" + value + "\"";
    }
}
