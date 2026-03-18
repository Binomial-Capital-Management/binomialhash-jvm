package com.binomialtechnologies.binomialhash.adapters;

import com.binomialtechnologies.binomialhash.tools.ToolSpec;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Google Gemini adapter — google.genai / Vertex AI.
 *
 * <p>Translates {@link ToolSpec} objects into function-declaration maps
 * that Gemini expects inside {@code types.Tool(function_declarations=[...])}.</p>
 *
 * <p>Wire format:
 * <pre>{@code
 * {
 *   "name": "bh_retrieve",           // must start with letter/underscore, max 64 chars
 *   "description": "...",
 *   "parameters": { "type": "object", "properties": {...}, "required": [...] }
 * }
 * }</pre></p>
 *
 * <p>Names must match {@code ^[a-zA-Z_][a-zA-Z0-9_.:-]{0,63}$}.</p>
 */
public final class GeminiAdapter {

    private static final Pattern NAME_RE = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.:\\-]{0,63}$");

    private GeminiAdapter() {}

    /**
     * Return function declaration maps for the Gemini API.
     *
     * @param specs ToolSpec objects
     * @return list of function declaration maps
     */
    public static List<Map<String, Object>> getGeminiTools(List<ToolSpec> specs) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpec spec : specs) {
            tools.add(specToGemini(spec));
        }
        return tools;
    }

    /**
     * Execute a tool call from a Gemini function_call part.
     *
     * @param specs the same specs used to generate the declarations
     * @param name  function_call.name
     * @param args  function_call.args — protobuf MapComposite or plain map
     * @return handler result
     */
    public static Object handleGeminiToolCall(List<ToolSpec> specs, String name, Object args) {
        return AdapterCommon.handleToolCall(specs, name, normalizeArgs(args));
    }

    private static Map<String, Object> specToGemini(ToolSpec spec) {
        validateName(spec.getName());
        Map<String, Object> decl = new LinkedHashMap<>();
        decl.put("name", spec.getName());
        decl.put("description", spec.getDescription());
        decl.put("parameters", new LinkedHashMap<>(spec.getInputSchema()));
        return decl;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeArgs(Object args) {
        if (args == null) return Collections.emptyMap();
        if (args instanceof Map) return (Map<String, Object>) args;
        try {
            return new LinkedHashMap<>((Map<String, Object>) args);
        } catch (ClassCastException e) {
            return Collections.emptyMap();
        }
    }

    private static void validateName(String name) {
        if (!NAME_RE.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Tool name '" + name + "' does not match Gemini's naming rules: "
                    + "must start with letter or underscore, max 64 chars, "
                    + "allowed chars: a-z A-Z 0-9 _ . : -");
        }
    }
}
