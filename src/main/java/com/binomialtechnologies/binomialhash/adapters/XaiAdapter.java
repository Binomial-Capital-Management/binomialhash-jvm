package com.binomialtechnologies.binomialhash.adapters;

import com.binomialtechnologies.binomialhash.tools.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * xAI / Grok adapter.
 *
 * <p>xAI's API is OpenAI-compatible — their tool schema is identical to
 * the OpenAI Responses API format ({@code type / name / description / parameters}).
 * The official xAI docs show using the OpenAI SDK with
 * {@code base_url="https://api.x.ai/v1"}.</p>
 *
 * <p>Wire format (identical to OpenAI Responses API):
 * <pre>{@code
 * {
 *   "type": "function",
 *   "name": "get_temperature",
 *   "description": "...",
 *   "parameters": { "type": "object", "properties": {...}, "required": [...] }
 * }
 * }</pre></p>
 *
 * <p>This class delegates to {@link OpenAiAdapter} since the formats are identical.
 * If xAI ever diverges, add xAI-specific logic here.</p>
 */
public final class XaiAdapter {

    private XaiAdapter() {}

    /**
     * Return tool definitions for the xAI / Grok API.
     * Format is identical to OpenAI Responses API.
     *
     * @param specs  ToolSpec objects
     * @param strict passed through to OpenAI adapter's strict-mode logic
     * @return list of tool definition maps
     */
    public static List<Map<String, Object>> getXaiTools(List<ToolSpec> specs, boolean strict) {
        return OpenAiAdapter.getOpenAiTools(specs, strict, "responses");
    }

    public static List<Map<String, Object>> getXaiTools(List<ToolSpec> specs) {
        return getXaiTools(specs, false);
    }

    /**
     * Execute a tool call from an xAI / Grok response.
     *
     * @param specs     the same specs used to generate the tool list
     * @param name      function_call.name
     * @param arguments function_call.arguments — JSON string or map
     * @return handler result
     */
    public static Object handleXaiToolCall(List<ToolSpec> specs, String name, Object arguments) {
        return OpenAiAdapter.handleOpenAiToolCall(specs, name, arguments);
    }
}
