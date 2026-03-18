package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;
import com.binomialtechnologies.binomialhash.adapters.AnthropicAdapter;
import com.binomialtechnologies.binomialhash.adapters.GeminiAdapter;
import com.binomialtechnologies.binomialhash.adapters.OpenAiAdapter;
import com.binomialtechnologies.binomialhash.adapters.XaiAdapter;
import com.binomialtechnologies.binomialhash.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Live spatial reasoning demo — multi-turn BH tool-use loop with factor data.
 * Uses the first available provider to answer a 3-part analytical question.
 */
public final class Example17_SpatialReasoningLive {

    private static final String SYSTEM = "You are a financial analyst. Use the BH tools to answer. Be concise.";
    private static final String USER_QUERY = "Analyze the dataset: 1) What's the mean return by sector? "
            + "2) Which sector has highest volatility? 3) What's the correlation between beta and return?";

    public static void main(String[] args) {
        boolean openai = hasKey("OPENAI_API_KEY");
        boolean anthropic = hasKey("ANTHROPIC_API_KEY");
        boolean gemini = hasKey("GOOGLE_API_KEY");
        boolean xai = hasKey("XAI_API_KEY");

        if (!openai && !anthropic && !gemini && !xai) {
            System.out.println("No API keys found. Set at least one of: OPENAI_API_KEY, ANTHROPIC_API_KEY, GOOGLE_API_KEY, XAI_API_KEY");
            return;
        }

        try {
            BinomialHash bh = new BinomialHash();
            List<Map<String, Object>> factorData = generateFactorData();
            String summary = bh.ingest(toJson(factorData), "factor_data");

            List<ToolSpec> specs = buildSpecs(bh);
            String answer = null;

            if (openai) {
                printSeparator("Using OpenAI");
                answer = runOpenAi(specs, summary);
            } else if (anthropic) {
                printSeparator("Using Anthropic");
                answer = runAnthropic(specs, summary);
            } else if (gemini) {
                printSeparator("Using Gemini");
                answer = runGemini(specs, summary);
            } else if (xai) {
                printSeparator("Using xAI");
                answer = runXai(specs, summary);
            }

            if (answer != null) {
                printSeparator("Final answer");
                System.out.println(answer);
            }

            printSeparator("Context stats");
            bh.contextStats().forEach((k, v) -> System.out.println("  " + k + ": " + v));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String runOpenAi(List<ToolSpec> specs, String summary) throws IOException, InterruptedException {
        List<Map<String, Object>> tools = OpenAiAdapter.getOpenAiTools(specs, false, "chat_completions");

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM + "\n\nStored dataset:\n" + summary));
        messages.add(Map.of("role", "user", "content", USER_QUERY));

        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + key("OPENAI_API_KEY"),
                "Content-Type", "application/json");

        for (int turn = 0; turn < 15; turn++) {
            Map<String, Object> body = Map.of(
                    "model", "gpt-5.4",
                    "messages", messages,
                    "tools", tools);

            var resp = post("https://api.openai.com/v1/chat/completions", headers, body);
            if (resp.statusCode() != 200) {
                System.err.println("API error " + resp.statusCode());
                return null;
            }

            JsonNode root = parseJson(resp.body());
            JsonNode choice = root.path("choices").get(0);
            String finishReason = choice.path("finish_reason").asText();
            JsonNode msg = choice.path("message");

            if ("stop".equals(finishReason)) {
                JsonNode content = msg.path("content");
                return content.isNull() ? "" : content.asText();
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
                    System.out.println("[Tool] " + name + " → " + content.length() + " chars");
                }
            }
        }
        return null;
    }

    private static String runAnthropic(List<ToolSpec> specs, String summary) throws IOException, InterruptedException {
        List<Map<String, Object>> tools = AnthropicAdapter.getAnthropicTools(specs);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "Stored dataset:\n" + summary + "\n\n" + USER_QUERY));

        Map<String, String> headers = Map.of(
                "x-api-key", key("ANTHROPIC_API_KEY"),
                "anthropic-version", "2023-06-01",
                "Content-Type", "application/json");

        StringBuilder answer = new StringBuilder();
        for (int turn = 0; turn < 15; turn++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "claude-sonnet-4-6");
            body.put("max_tokens", 1024);
            body.put("system", SYSTEM);
            body.put("messages", messages);
            body.put("tools", tools);

            var resp = post("https://api.anthropic.com/v1/messages", headers, body);
            if (resp.statusCode() != 200) {
                System.err.println("API error " + resp.statusCode());
                return null;
            }

            JsonNode root = parseJson(resp.body());
            JsonNode content = root.get("content");
            if (content == null || !content.isArray()) return answer.toString();

            List<Map<String, Object>> toolResults = new ArrayList<>();
            List<Map<String, Object>> assistantContent = new ArrayList<>();
            for (JsonNode block : content) {
                assistantContent.add(parseMap(toJson(block)));
                if ("tool_use".equals(block.path("type").asText())) {
                    String name = block.get("name").asText();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = block.has("input") ? parseMap(block.get("input").toString()) : Map.of();
                    Object result = AnthropicAdapter.handleAnthropicToolUse(specs, name, input);
                    toolResults.add(Map.of(
                            "type", "tool_result",
                            "tool_use_id", block.get("id").asText(),
                            "content", result instanceof Map ? toJson(result) : String.valueOf(result)));
                    System.out.println("[Tool] " + name + " → " + (result instanceof Map ? toJson(result) : result).toString().length() + " chars");
                } else if ("text".equals(block.path("type").asText()) && block.has("text")) {
                    answer.append(block.get("text").asText());
                }
            }

            if (toolResults.isEmpty()) return answer.toString();
            messages.add(Map.of("role", "assistant", "content", assistantContent));
            messages.add(Map.of("role", "user", "content", toolResults));
        }
        return answer.toString();
    }

    private static String runGemini(List<ToolSpec> specs, String summary) throws IOException, InterruptedException {
        List<Map<String, Object>> funcDecls = GeminiAdapter.getGeminiTools(specs);

        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", "Stored dataset:\n" + summary + "\n\n" + USER_QUERY))));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent?key=" + key("GOOGLE_API_KEY");
        Map<String, String> headers = Map.of("Content-Type", "application/json");

        StringBuilder answer = new StringBuilder();
        for (int turn = 0; turn < 15; turn++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", contents);
            body.put("tools", List.of(Map.of("function_declarations", funcDecls)));
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM))));

            var resp = post(url, headers, body);
            if (resp.statusCode() != 200) {
                System.err.println("API error " + resp.statusCode());
                return null;
            }

            JsonNode root = parseJson(resp.body());
            JsonNode candidates = root.get("candidates");
            if (candidates == null || !candidates.isArray() || candidates.isEmpty()) return answer.toString();

            JsonNode parts = candidates.get(0).path("content").path("parts");
            List<Map<String, Object>> modelParts = new ArrayList<>();
            List<Map<String, Object>> responseParts = new ArrayList<>();

            for (JsonNode part : parts) {
                JsonNode fc = part.get("functionCall");
                if (fc != null) {
                    String name = fc.get("name").asText();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = fc.has("args") ? parseMap(fc.get("args").toString()) : Map.of();
                    Object result = GeminiAdapter.handleGeminiToolCall(specs, name, args);
                    modelParts.add(parseMap(toJson(part)));
                    responseParts.add(Map.of(
                            "functionResponse", Map.of(
                                    "name", name,
                                    "response", Map.of("result", result))));
                    System.out.println("[Tool] " + name + " → " + (result instanceof Map ? toJson(result) : result).toString().length() + " chars");
                } else if (part.has("text")) {
                    answer.append(part.get("text").asText());
                    modelParts.add(parseMap(toJson(part)));
                }
            }

            if (responseParts.isEmpty()) return answer.toString();
            contents.add(Map.of("role", "model", "parts", modelParts));
            contents.add(Map.of("role", "user", "parts", responseParts));
        }
        return answer.toString();
    }

    private static String runXai(List<ToolSpec> specs, String summary) throws IOException, InterruptedException {
        List<Map<String, Object>> tools = XaiAdapter.getXaiTools(specs);

        List<Object> input = new ArrayList<>();
        input.add(Map.of("role", "user", "content", "Data loaded: " + summary + "\n\n" + USER_QUERY));

        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + key("XAI_API_KEY"),
                "Content-Type", "application/json");

        StringBuilder answer = new StringBuilder();

        for (int turn = 0; turn < 15; turn++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "grok-4-1-fast-reasoning");
            body.put("instructions", SYSTEM);
            body.put("input", input);
            body.put("tools", tools);

            var resp = post("https://api.x.ai/v1/responses", headers, body);
            if (resp.statusCode() != 200) {
                System.err.println("API error " + resp.statusCode() + ": " + resp.body().substring(0, Math.min(300, resp.body().length())));
                return null;
            }

            JsonNode root = parseJson(resp.body());
            JsonNode output = root.path("output");

            boolean hadToolCall = false;
            for (JsonNode item : output) {
                input.add(parseMap(item.toString()));
                String type = item.path("type").asText();
                if ("function_call".equals(type)) {
                    hadToolCall = true;
                    String name = item.path("name").asText();
                    String arguments = item.path("arguments").asText();
                    Object result = XaiAdapter.handleXaiToolCall(specs, name, arguments);
                    String content = result instanceof Map ? toJson(result) : String.valueOf(result);
                    input.add(Map.of("type", "function_call_output", "call_id", item.path("call_id").asText(), "output", content));
                    System.out.println("[Tool] " + name + " → " + content.length() + " chars");
                } else if ("message".equals(type)) {
                    JsonNode contentArr = item.path("content");
                    for (JsonNode c : contentArr) {
                        if ("output_text".equals(c.path("type").asText()) || "text".equals(c.path("type").asText())) {
                            answer.append(c.path("text").asText());
                        }
                    }
                }
            }
            String outputText = root.path("output_text").asText("");
            if (!outputText.isBlank() && answer.isEmpty()) answer.append(outputText);

            if (!hadToolCall) break;
        }
        return answer.isEmpty() ? null : answer.toString();
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
        specs.add(new ToolSpec("bh_query", "Query rows with a where clause.",
                Map.of("type", "object", "properties", Map.of(
                        "key", Map.of("type", "string"),
                        "where_json", Map.of("type", "string"),
                        "sort_by", Map.of("type", "string"),
                        "sort_desc", Map.of("type", "boolean"),
                        "limit", Map.of("type", "integer"),
                        "columns", Map.of("type", "array", "items", Map.of("type", "string"))),
                        "required", List.of("key")),
                args -> bh.query((String) args.get("key"),
                        args.containsKey("where_json") ? (String) args.get("where_json") : "{}",
                        (String) args.get("sort_by"),
                        args.containsKey("sort_desc") ? Boolean.TRUE.equals(args.get("sort_desc")) : true,
                        args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 25,
                        toStrList(args.get("columns"))),
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
