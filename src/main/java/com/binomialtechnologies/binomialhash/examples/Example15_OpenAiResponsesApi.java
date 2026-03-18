package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;
import com.binomialtechnologies.binomialhash.adapters.OpenAiAdapter;
import com.binomialtechnologies.binomialhash.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * OpenAI Responses API flow (NOT Chat Completions — the new API).
 *
 * <p>Uses POST to /v1/responses with input array, handles function_call /
 * function_call_output items for multi-turn tool use.</p>
 */
public final class Example15_OpenAiResponsesApi {

    private static final String INSTRUCTIONS = "You are a financial analyst. A dataset has been loaded. "
            + "Use the BH tools to answer questions. Be concise.";
    private static final String USER_MSG = "Group the data by sector and show average price per sector.";

    public static void main(String[] args) {
        if (!hasKey("OPENAI_API_KEY")) {
            System.out.println("OPENAI_API_KEY not set. Skipping example.");
            return;
        }

        try {
            BinomialHash bh = new BinomialHash();
            List<Map<String, Object>> marketData = generateMarketData();
            String summary = bh.ingest(toJson(marketData), "market_data");

            List<ToolSpec> specs = buildSpecs(bh);
            List<Map<String, Object>> tools = OpenAiAdapter.getOpenAiTools(specs, false, "responses");

            List<Map<String, Object>> inputList = new ArrayList<>();
            inputList.add(Map.of("role", "user", "content", summary + "\n\n" + USER_MSG));

            Map<String, String> headers = Map.of(
                    "Authorization", "Bearer " + key("OPENAI_API_KEY"),
                    "Content-Type", "application/json");

            for (int turn = 0; turn < 15; turn++) {
                Map<String, Object> body = Map.of(
                        "model", "gpt-5.4",
                        "instructions", INSTRUCTIONS,
                        "tools", tools,
                        "input", inputList);

                var resp = post("https://api.openai.com/v1/responses", headers, body);
                if (resp.statusCode() != 200) {
                    System.err.println("API error " + resp.statusCode() + ": " + resp.body());
                    return;
                }

                JsonNode root = parseJson(resp.body());
                JsonNode output = root.get("output");
                if (output == null || !output.isArray()) {
                    System.err.println("Unexpected response: no output array");
                    return;
                }

                List<Map<String, Object>> outputItems = new ArrayList<>();
                for (JsonNode item : output) {
                    outputItems.add(parseMap(toJson(item)));
                }
                inputList.addAll(outputItems);

                boolean hasToolCalls = false;
                for (JsonNode item : output) {
                    String type = item.has("type") ? item.get("type").asText() : "";
                    if ("function_call".equals(type)) {
                        hasToolCalls = true;
                        String name = item.get("name").asText();
                        String arguments = item.has("arguments") ? item.get("arguments").asText() : "{}";
                        String callId = item.has("call_id") ? item.get("call_id").asText() : "fc_" + turn;

                        Object result = OpenAiAdapter.handleOpenAiToolCall(specs, name, arguments);
                        String outputStr = result instanceof Map ? toJson(result) : String.valueOf(result);

                        inputList.add(Map.of(
                                "type", "function_call_output",
                                "call_id", callId,
                                "output", outputStr));
                        System.out.println("[Turn " + turn + "] " + name + " → " + outputStr.length() + " chars");
                    }
                }

                if (!hasToolCalls) {
                    JsonNode outputText = root.get("output_text");
                    if (outputText != null && !outputText.isNull()) {
                        System.out.println("\n[Assistant] " + outputText.asText());
                    } else {
                        for (JsonNode item : output) {
                            String type = item.has("type") ? item.get("type").asText() : "";
                            if ("output_text".equals(type) && item.has("text")) {
                                System.out.println("\n[Assistant] " + item.get("text").asText());
                                break;
                            }
                            if ("message".equals(type) && item.has("content")) {
                                JsonNode content = item.get("content");
                                if (content.isArray()) {
                                    for (JsonNode c : content) {
                                        if (c.has("type") && "output_text".equals(c.path("type").asText())
                                                && c.has("text")) {
                                            System.out.println("\n[Assistant] " + c.get("text").asText());
                                            break;
                                        }
                                    }
                                }
                                break;
                            }
                        }
                    }
                    break;
                }
            }

            printSeparator("Context stats");
            bh.contextStats().forEach((k, v) -> System.out.println("  " + k + ": " + v));
        } catch (IOException | InterruptedException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    static List<ToolSpec> buildSpecs(BinomialHash bh) {
        List<ToolSpec> specs = new ArrayList<>();
        specs.add(new ToolSpec("bh_retrieve", "Retrieve rows from stored dataset.",
                Map.of("type", "object", "properties", Map.of(
                        "key", Map.of("type", "string"),
                        "limit", Map.of("type", "integer"),
                        "sort_by", Map.of("type", "string")),
                        "required", List.of("key")),
                args -> bh.retrieve((String) args.get("key"), 0,
                        args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 25,
                        (String) args.get("sort_by"), true, null),
                "retrieval"));
        specs.add(new ToolSpec("bh_aggregate", "Compute aggregate on a column.",
                Map.of("type", "object", "properties", Map.of(
                        "key", Map.of("type", "string"),
                        "column", Map.of("type", "string"),
                        "func", Map.of("type", "string")),
                        "required", List.of("key", "column", "func")),
                args -> bh.aggregate((String) args.get("key"), (String) args.get("column"), (String) args.get("func")),
                "retrieval"));
        specs.add(new ToolSpec("bh_group_by", "Group rows and aggregate.",
                Map.of("type", "object", "properties", Map.of(
                        "key", Map.of("type", "string"),
                        "group_cols", Map.of("type", "string"),
                        "agg_json", Map.of("type", "string"),
                        "sort_by", Map.of("type", "string"),
                        "limit", Map.of("type", "integer")),
                        "required", List.of("key", "group_cols", "agg_json")),
                args -> {
                    String gc = (String) args.getOrDefault("group_cols", "[]");
                    List<String> groupCols = gc.replaceAll("[\\[\\]\"]", "").isBlank()
                            ? List.of() : Arrays.asList(gc.replaceAll("[\\[\\]\"]", "").split("\\s*,\\s*"));
                    return bh.groupBy((String) args.get("key"), groupCols,
                            (String) args.get("agg_json"),
                            (String) args.get("sort_by"), true,
                            args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 50);
                },
                "retrieval"));
        return specs;
    }
}
