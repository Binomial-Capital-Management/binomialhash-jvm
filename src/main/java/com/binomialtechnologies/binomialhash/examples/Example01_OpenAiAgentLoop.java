package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;
import com.binomialtechnologies.binomialhash.adapters.OpenAiAdapter;
import com.binomialtechnologies.binomialhash.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * OpenAI Chat Completions tool-use loop with BinomialHash.
 * Uses bh_retrieve, bh_aggregate, bh_query, bh_group_by to answer questions.
 */
public final class Example01_OpenAiAgentLoop {

    private static final String SYSTEM = "You are a financial analyst. A dataset has been loaded. "
            + "Use the BH tools to answer questions. Be concise.";
    private static final String USER_MSG = "Which sector has the highest average price? "
            + "Group by sector and show mean price.";

    public static void main(String[] args) {
        if (!hasKey("OPENAI_API_KEY")) {
            System.out.println("OPENAI_API_KEY not set. Skipping example.");
            return;
        }

        try {
            BinomialHash bh = new BinomialHash();
            List<Map<String, Object>> rows = generateMarketData();
            String summary = bh.ingest(toJson(rows), "market");

            List<ToolSpec> specs = buildSpecs(bh);
            List<Map<String, Object>> tools = OpenAiAdapter.getOpenAiTools(specs, false, "chat_completions");

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SYSTEM + "\n\nStored dataset:\n" + summary));
            messages.add(Map.of("role", "user", "content", USER_MSG));

            Map<String, String> headers = Map.of(
                    "Authorization", "Bearer " + key("OPENAI_API_KEY"),
                    "Content-Type", "application/json");

            for (int turn = 0; turn < 15; turn++) {
                Map<String, Object> body = Map.of(
                        "model", "gpt-5.4",
                        "messages", messages,
                        "tools", tools);

                var resp = post("https://api.openai.com/v1/chat/completions", headers, body);
                JsonNode root = parseJson(resp.body());

                JsonNode choice = root.path("choices").get(0);
                String finishReason = choice.path("finish_reason").asText();
                JsonNode msg = choice.path("message");

                if ("stop".equals(finishReason)) {
                    JsonNode content = msg.path("content");
                    System.out.println("\n[Assistant] " + (content.isNull() ? "" : content.asText()));
                    break;
                }

                if ("tool_calls".equals(finishReason)) {
                    messages.add(parseMap(toJson(msg)));
                    JsonNode toolCalls = msg.path("tool_calls");
                    for (JsonNode tc : toolCalls) {
                        String name = tc.path("function").path("name").asText();
                        String arguments = tc.path("function").path("arguments").asText();
                        Object result = OpenAiAdapter.handleOpenAiToolCall(specs, name, arguments);
                        String content = result instanceof Map ? toJson(result) : String.valueOf(result);
                        messages.add(Map.of(
                                "role", "tool",
                                "tool_call_id", tc.path("id").asText(),
                                "content", content));
                        System.out.println("[Turn " + turn + "] " + name + " → " + content.length() + " chars");
                    }
                }
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
                        "columns", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Columns to return")),
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

        specs.add(new ToolSpec("bh_group_by", "Group by columns and aggregate.",
                Map.of("type", "object", "properties", Map.of(
                        "key", Map.of("type", "string", "description", "Dataset key"),
                        "group_cols", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Group columns"),
                        "agg_json", Map.of("type", "string", "description", "JSON array of {column, func, alias}"),
                        "sort_by", Map.of("type", "string", "description", "Sort column"),
                        "sort_desc", Map.of("type", "boolean", "description", "Descending sort"),
                        "limit", Map.of("type", "integer", "description", "Max rows")),
                        "required", List.of("key", "group_cols", "agg_json")),
                a -> bh.groupBy((String) a.get("key"),
                        toStrList(a.get("group_cols")),
                        a.containsKey("agg_json") ? (a.get("agg_json") instanceof String ? (String) a.get("agg_json") : toJson(a.get("agg_json"))) : "[]",
                        (String) a.get("sort_by"),
                        a.containsKey("sort_desc") ? Boolean.TRUE.equals(a.get("sort_desc")) : true,
                        a.containsKey("limit") ? ((Number) a.get("limit")).intValue() : 25),
                "stats"));

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
