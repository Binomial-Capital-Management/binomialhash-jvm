package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;
import com.binomialtechnologies.binomialhash.adapters.AnthropicAdapter;
import com.binomialtechnologies.binomialhash.adapters.GeminiAdapter;
import com.binomialtechnologies.binomialhash.adapters.OpenAiAdapter;
import com.binomialtechnologies.binomialhash.adapters.XaiAdapter;
import com.binomialtechnologies.binomialhash.tools.ToolSpec;

import java.util.*;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Demonstrates creating ToolSpec objects and converting them to all 4 provider formats.
 */
public class Example04_ToolSpecBridge {

    public static void main(String[] args) {
        BinomialHash bh = new BinomialHash();
        List<Map<String, Object>> marketData = generateMarketData();
        String json = toJson(marketData);
        bh.ingest(json, "market_data");

        List<ToolSpec> specs = buildToolSpecs(bh);

        printSeparator("OpenAI format");
        List<Map<String, Object>> openAiTools = OpenAiAdapter.getOpenAiTools(specs);
        System.out.println(toJson(openAiTools));
        System.out.println("Tool properties count: " + countToolProperties(openAiTools));

        printSeparator("Anthropic format");
        List<Map<String, Object>> anthropicTools = AnthropicAdapter.getAnthropicTools(specs);
        System.out.println(toJson(anthropicTools));
        System.out.println("Tool properties count: " + countToolProperties(anthropicTools));

        printSeparator("Gemini format");
        List<Map<String, Object>> geminiTools = GeminiAdapter.getGeminiTools(specs);
        System.out.println(toJson(geminiTools));
        System.out.println("Tool properties count: " + countToolProperties(geminiTools));

        printSeparator("xAI format");
        List<Map<String, Object>> xaiTools = XaiAdapter.getXaiTools(specs);
        System.out.println(toJson(xaiTools));
        System.out.println("Tool properties count: " + countToolProperties(xaiTools));
    }

    private static List<ToolSpec> buildToolSpecs(BinomialHash bh) {
        Map<String, Object> retrieveSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "key", Map.of("type", "string", "description", "Slot key"),
                        "offset", Map.of("type", "integer", "description", "Row offset"),
                        "limit", Map.of("type", "integer", "description", "Max rows")));

        Map<String, Object> aggregateSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "key", Map.of("type", "string"),
                        "column", Map.of("type", "string"),
                        "func", Map.of("type", "string", "enum", List.of("sum", "avg", "min", "max", "count"))));

        Map<String, Object> schemaSchema = Map.of(
                "type", "object",
                "properties", Map.of("key", Map.of("type", "string")));

        return List.of(
                new ToolSpec("bh_retrieve", "Retrieve rows from a BH slot", retrieveSchema,
                        args -> bh.retrieve((String) args.get("key"),
                                ((Number) args.getOrDefault("offset", 0)).intValue(),
                                ((Number) args.getOrDefault("limit", 25)).intValue(),
                                null, true, null)),
                new ToolSpec("bh_aggregate", "Run aggregation on a column", aggregateSchema,
                        args -> bh.aggregate((String) args.get("key"),
                                (String) args.get("column"),
                                (String) args.get("func"))),
                new ToolSpec("bh_schema", "Get schema for a slot", schemaSchema,
                        args -> bh.schema((String) args.get("key"))));
    }

    @SuppressWarnings("unchecked")
    private static int countToolProperties(List<Map<String, Object>> tools) {
        int total = 0;
        for (Map<String, Object> tool : tools) {
            Map<String, Object> params = (Map<String, Object>) tool.getOrDefault("parameters",
                    tool.getOrDefault("input_schema", Map.of()));
            Object props = params.get("properties");
            if (props instanceof Map) total += ((Map<?, ?>) props).size();
        }
        return total;
    }
}
