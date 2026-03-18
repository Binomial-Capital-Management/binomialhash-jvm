package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;
import com.binomialtechnologies.binomialhash.adapters.GeminiAdapter;
import com.binomialtechnologies.binomialhash.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Google Gemini function-calling loop with BinomialHash.
 * Uses bh_retrieve, bh_aggregate, bh_query to answer gene expression questions.
 */
public final class Example03_GeminiFunctionCalling {

    private static final String SYSTEM = "You are a genomics analyst. A dataset has been loaded. "
            + "Use the BH tools to answer questions. Be concise.";
    private static final String USER_MSG = "What is the average expression level? "
            + "What tissue has the highest fold change?";

    public static void main(String[] args) {
        if (!hasKey("GOOGLE_API_KEY")) {
            System.out.println("GOOGLE_API_KEY not set. Skipping example.");
            return;
        }

        try {
            BinomialHash bh = new BinomialHash();
            List<Map<String, Object>> rows = generateGeneData();
            String summary = bh.ingest(toJson(rows), "gene_expression");

            List<ToolSpec> specs = buildSpecs(bh);
            List<Map<String, Object>> decls = GeminiAdapter.getGeminiTools(specs);

            List<Map<String, Object>> contents = new ArrayList<>();
            contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", "Stored dataset:\n" + summary + "\n\n" + USER_MSG))));

            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent?key="
                    + key("GOOGLE_API_KEY");
            Map<String, String> headers = Map.of("Content-Type", "application/json");

            for (int turn = 0; turn < 15; turn++) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("contents", contents);
                body.put("system_instruction", Map.of("parts", List.of(Map.of("text", SYSTEM))));
                body.put("tools", List.of(Map.of("function_declarations", decls)));

                var resp = post(url, headers, body);
                JsonNode root = parseJson(resp.body());

                JsonNode candidates = root.path("candidates");
                if (candidates.isEmpty()) {
                    System.err.println("No candidates in response");
                    break;
                }
                JsonNode content = candidates.get(0).path("content");
                JsonNode parts = content.path("parts");

                List<Map<String, Object>> modelParts = new ArrayList<>();
                List<Map<String, Object>> functionResponses = new ArrayList<>();
                boolean hasText = false;

                for (JsonNode part : parts) {
                    JsonNode fc = part.path("functionCall");
                    if (!fc.isMissingNode()) {
                        String name = fc.path("name").asText();
                        JsonNode argsNode = fc.path("args");
                        Map<String, Object> argsMap = argsNode.isObject() ? parseMap(argsNode.toString()) : Map.of();
                        Object result = GeminiAdapter.handleGeminiToolCall(specs, name, argsMap);
                        Map<String, Object> response = result instanceof Map ? (Map<String, Object>) result : Map.of("result", result);
                        functionResponses.add(Map.of("functionResponse", Map.of("name", name, "response", response)));
                        modelParts.add(parseMap(part.toString()));
                        System.out.println("[Turn " + turn + "] " + name + " → " + toJson(result).length() + " chars");
                    } else {
                        JsonNode text = part.path("text");
                        if (!text.isMissingNode()) {
                            System.out.println("\n[Gemini] " + text.asText());
                            hasText = true;
                        }
                        modelParts.add(parseMap(part.toString()));
                    }
                }

                contents.add(Map.of("role", "model", "parts", modelParts));
                if (functionResponses.isEmpty()) break;
                contents.add(Map.of("role", "user", "parts", functionResponses));
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
