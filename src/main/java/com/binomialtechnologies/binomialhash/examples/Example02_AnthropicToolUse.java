package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;
import com.binomialtechnologies.binomialhash.adapters.AnthropicAdapter;
import com.binomialtechnologies.binomialhash.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Anthropic Messages API tool-use loop with BinomialHash.
 * Uses bh_retrieve, bh_aggregate, bh_query to answer earnings questions.
 */
public final class Example02_AnthropicToolUse {

    private static final String SYSTEM = "You are a financial analyst. A dataset has been loaded. "
            + "Use the BH tools to answer questions. Be concise.";
    private static final String USER_MSG = "What is the average EPS? Which quarter had the highest revenue?";

    public static void main(String[] args) {
        if (!hasKey("ANTHROPIC_API_KEY")) {
            System.out.println("ANTHROPIC_API_KEY not set. Skipping example.");
            return;
        }

        try {
            BinomialHash bh = new BinomialHash();
            List<Map<String, Object>> rows = generateEarningsData("AAPL");
            String summary = bh.ingest(toJson(rows), "earnings_AAPL");

            List<ToolSpec> specs = buildSpecs(bh);
            List<Map<String, Object>> tools = AnthropicAdapter.getAnthropicTools(specs);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", "Stored dataset:\n" + summary + "\n\n" + USER_MSG));

            Map<String, String> headers = Map.of(
                    "x-api-key", key("ANTHROPIC_API_KEY"),
                    "anthropic-version", "2023-06-01",
                    "Content-Type", "application/json");

            for (int turn = 0; turn < 15; turn++) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", "claude-sonnet-4-6");
                body.put("max_tokens", 1024);
                body.put("system", SYSTEM);
                body.put("tools", tools);
                body.put("messages", messages);

                var resp = post("https://api.anthropic.com/v1/messages", headers, body);
                JsonNode root = parseJson(resp.body());

                JsonNode contentArr = root.path("content");
                List<Map<String, Object>> toolResults = new ArrayList<>();
                List<Map<String, Object>> assistantContent = new ArrayList<>();

                for (JsonNode block : contentArr) {
                    String type = block.path("type").asText();
                    assistantContent.add(parseMap(block.toString()));
                    if ("text".equals(type)) {
                        System.out.println("\n[Claude] " + block.path("text").asText());
                    } else if ("tool_use".equals(type)) {
                        String name = block.path("name").asText();
                        JsonNode inputNode = block.path("input");
                        Map<String, Object> inputMap = inputNode.isObject() ? parseMap(inputNode.toString()) : Map.of();
                        Object result = AnthropicAdapter.handleAnthropicToolUse(specs, name, inputMap);
                        String content = result instanceof Map ? toJson(result) : String.valueOf(result);
                        toolResults.add(Map.of(
                                "type", "tool_result",
                                "tool_use_id", block.path("id").asText(),
                                "content", content));
                        System.out.println("[Turn " + turn + "] " + name + " → " + content.length() + " chars");
                    }
                }

                messages.add(Map.of("role", "assistant", "content", assistantContent));
                if (toolResults.isEmpty()) break;
                messages.add(Map.of("role", "user", "content", toolResults));
            }

            System.out.println("\n=== Context stats ===");
            bh.contextStats().forEach((k, v) -> System.out.println("  " + k + ": " + v));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ToolSpec> buildSpecs(BinomialHash bh) {
        List<ToolSpec> specs = new ArrayList<>();

        specs.add(new ToolSpec("bh_retrieve", "Retrieve rows from a stored dataset.",
                Map.of("type", "object", "properties", Map.of(
                        "key", Map.of("type", "string", "description", "Dataset key"),
                        "offset", Map.of("type", "integer", "description", "Start offset"),
                        "limit", Map.of("type", "integer", "description", "Max rows"),
                        "sort_by", Map.of("type", "string", "description", "Sort column"),
                        "sort_desc", Map.of("type", "boolean", "description", "Descending sort"),
                        "columns", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Columns")),
                        "required", List.of("key")),
                a -> bh.retrieve((String) a.get("key"),
                        a.containsKey("offset") ? ((Number) a.get("offset")).intValue() : 0,
                        a.containsKey("limit") ? ((Number) a.get("limit")).intValue() : 25,
                        (String) a.get("sort_by"),
                        a.containsKey("sort_desc") ? Boolean.TRUE.equals(a.get("sort_desc")) : true,
                        toStrList(a.get("columns"))),
                "retrieval"));

        specs.add(new ToolSpec("bh_aggregate", "Aggregate a column (sum, mean, min, max, count).",
                Map.of("type", "object", "properties", Map.of(
                        "key", Map.of("type", "string", "description", "Dataset key"),
                        "column", Map.of("type", "string", "description", "Column name"),
                        "func", Map.of("type", "string", "description", "Aggregation: sum, mean, min, max, count")),
                        "required", List.of("key", "column", "func")),
                a -> bh.aggregate((String) a.get("key"), (String) a.get("column"), (String) a.get("func")),
                "stats"));

        specs.add(new ToolSpec("bh_query", "Query rows with a where clause.",
                Map.of("type", "object", "properties", Map.of(
                        "key", Map.of("type", "string", "description", "Dataset key"),
                        "where_json", Map.of("type", "string", "description", "JSON where clause"),
                        "sort_by", Map.of("type", "string", "description", "Sort column"),
                        "sort_desc", Map.of("type", "boolean", "description", "Descending sort"),
                        "limit", Map.of("type", "integer", "description", "Max rows"),
                        "columns", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Columns")),
                        "required", List.of("key")),
                a -> bh.query((String) a.get("key"),
                        a.containsKey("where_json") ? (String) a.get("where_json") : "{}",
                        (String) a.get("sort_by"),
                        a.containsKey("sort_desc") ? Boolean.TRUE.equals(a.get("sort_desc")) : true,
                        a.containsKey("limit") ? ((Number) a.get("limit")).intValue() : 25,
                        toStrList(a.get("columns"))),
                "retrieval"));

        return specs;
    }

    private static List<String> toStrList(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object x : list) out.add(x != null ? x.toString() : "");
            return out;
        }
        if (o instanceof String s) {
            JsonNode arr = parseJson(s);
            if (arr.isArray()) {
                List<String> out = new ArrayList<>();
                for (JsonNode n : arr) out.add(n.asText());
                return out;
            }
        }
        return null;
    }
}
