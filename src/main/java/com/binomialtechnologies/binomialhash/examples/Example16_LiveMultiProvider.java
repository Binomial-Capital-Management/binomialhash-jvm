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
 * Multi-provider benchmark — runs the same BH task across available providers.
 * Compares OpenAI, Anthropic, Gemini, and xAI with tool-use loops.
 */
public final class Example16_LiveMultiProvider {

    private static final String SYSTEM = "You are a financial analyst. Use the BH tools to answer. Be concise.";
    private static final String USER_QUERY = "Which sector has the highest average price? And what's the mean return_1d?";

    private static final class ProviderResult {
        final String provider;
        final String model;
        boolean success;
        String answer = "";
        int toolCalls;
        long latencyMs;
        String error = "";

        ProviderResult(String provider, String model) {
            this.provider = provider;
            this.model = model;
        }
    }

    public static void main(String[] args) {
        List<String> available = new ArrayList<>();
        boolean openai = hasKey("OPENAI_API_KEY");
        boolean anthropic = hasKey("ANTHROPIC_API_KEY");
        boolean gemini = hasKey("GOOGLE_API_KEY");
        boolean xai = hasKey("XAI_API_KEY");

        if (openai) available.add("openai");
        if (anthropic) available.add("anthropic");
        if (gemini) available.add("gemini");
        if (xai) available.add("xai");

        printSeparator("Provider status");
        System.out.println("  openai " + (openai ? "ready" : "skipped (no key)"));
        System.out.println("  anthropic " + (anthropic ? "ready" : "skipped (no key)"));
        System.out.println("  gemini " + (gemini ? "ready" : "skipped (no key)"));
        System.out.println("  xai " + (xai ? "ready" : "skipped (no key)"));
        System.out.println();

        if (available.isEmpty()) {
            System.out.println("No API keys found. Set at least one of: OPENAI_API_KEY, ANTHROPIC_API_KEY, GOOGLE_API_KEY, XAI_API_KEY");
            return;
        }

        try {
            BinomialHash bh = new BinomialHash();
            List<Map<String, Object>> marketData = generateMarketData();
            String summary = bh.ingest(toJson(marketData), "market_data");
            String key = bh.keys().get(0).get("key").toString();

            List<ToolSpec> specs = buildSpecs(bh);
            List<ProviderResult> results = new ArrayList<>();

            if (openai) {
                ProviderResult r = testOpenAi(specs, summary, key);
                results.add(r);
            }
            if (anthropic) {
                ProviderResult r = testAnthropic(specs, summary, key);
                results.add(r);
            }
            if (gemini) {
                ProviderResult r = testGemini(specs, summary, key);
                results.add(r);
            }
            if (xai) {
                ProviderResult r = testXai(specs, summary, key);
                results.add(r);
            }

            printSeparator("Comparison");
            System.out.printf("%-12s %-30s %4s %6s %8s  %s%n", "Provider", "Model", "OK", "Tools", "Latency", "Answer preview");
            System.out.println("-".repeat(100));
            for (ProviderResult r : results) {
                String ok = r.success ? "yes" : "NO";
                String preview = (r.answer != null && !r.answer.isBlank() ? r.answer : r.error).replace("\n", " ");
                if (preview.length() > 40) preview = preview.substring(0, 40) + "...";
                System.out.printf("%-12s %-30s %4s %6d %7dms  %s%n", r.provider, r.model, ok, r.toolCalls, r.latencyMs, preview);
            }

            System.out.println("\n=== Context stats ===");
            bh.contextStats().forEach((k, v) -> System.out.println("  " + k + ": " + v));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static ProviderResult testOpenAi(List<ToolSpec> specs, String summary, String key) {
        List<Map<String, Object>> tools = OpenAiAdapter.getOpenAiTools(specs, false, "chat_completions");
        ProviderResult r = new ProviderResult("OpenAI", "gpt-5.4");

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM + "\n\nStored dataset:\n" + summary));
        messages.add(Map.of("role", "user", "content", USER_QUERY));

        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + key("OPENAI_API_KEY"),
                "Content-Type", "application/json");

        long t0 = System.currentTimeMillis();
        try {
            for (int turn = 0; turn < 15; turn++) {
                Map<String, Object> body = Map.of(
                        "model", "gpt-5.4",
                        "messages", messages,
                        "tools", tools);

                var resp = post("https://api.openai.com/v1/chat/completions", headers, body);
                if (resp.statusCode() != 200) {
                    r.error = "HTTP " + resp.statusCode();
                    break;
                }

                JsonNode root = parseJson(resp.body());
                JsonNode choice = root.path("choices").get(0);
                String finishReason = choice.path("finish_reason").asText();
                JsonNode msg = choice.path("message");

                if ("stop".equals(finishReason)) {
                    JsonNode content = msg.path("content");
                    r.answer = content.isNull() ? "" : content.asText();
                    r.success = true;
                    break;
                }

                if ("tool_calls".equals(finishReason)) {
                    messages.add(parseMap(toJson(msg)));
                    JsonNode toolCalls = msg.path("tool_calls");
                    for (JsonNode tc : toolCalls) {
                        r.toolCalls++;
                        String name = tc.path("function").path("name").asText();
                        String arguments = tc.path("function").path("arguments").asText();
                        Object result = OpenAiAdapter.handleOpenAiToolCall(specs, name, arguments);
                        String content = result instanceof Map ? toJson(result) : String.valueOf(result);
                        messages.add(Map.of(
                                "role", "tool",
                                "tool_call_id", tc.path("id").asText(),
                                "content", content));
                    }
                }
            }
        } catch (Exception e) {
            r.error = e.getMessage();
        }
        r.latencyMs = System.currentTimeMillis() - t0;
        return r;
    }

    private static ProviderResult testAnthropic(List<ToolSpec> specs, String summary, String key) {
        List<Map<String, Object>> tools = AnthropicAdapter.getAnthropicTools(specs);
        ProviderResult r = new ProviderResult("Anthropic", "claude-sonnet-4-6");

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "Stored dataset:\n" + summary + "\n\n" + USER_QUERY));

        Map<String, String> headers = Map.of(
                "x-api-key", key("ANTHROPIC_API_KEY"),
                "anthropic-version", "2023-06-01",
                "Content-Type", "application/json");

        long t0 = System.currentTimeMillis();
        try {
            for (int turn = 0; turn < 15; turn++) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", "claude-sonnet-4-6");
                body.put("max_tokens", 1024);
                body.put("system", SYSTEM);
                body.put("messages", messages);
                body.put("tools", tools);

                var resp = post("https://api.anthropic.com/v1/messages", headers, body);
                if (resp.statusCode() != 200) {
                    r.error = "HTTP " + resp.statusCode();
                    break;
                }

                JsonNode root = parseJson(resp.body());
                JsonNode content = root.get("content");
                if (content == null || !content.isArray()) {
                    r.error = "No content in response";
                    break;
                }

                List<Map<String, Object>> toolResults = new ArrayList<>();
                List<Map<String, Object>> assistantContent = new ArrayList<>();
                for (JsonNode block : content) {
                    assistantContent.add(parseMap(toJson(block)));
                    if ("tool_use".equals(block.path("type").asText())) {
                        r.toolCalls++;
                        String name = block.get("name").asText();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> input = block.has("input") ? parseMap(block.get("input").toString()) : Map.of();
                        Object result = AnthropicAdapter.handleAnthropicToolUse(specs, name, input);
                        toolResults.add(Map.of(
                                "type", "tool_result",
                                "tool_use_id", block.get("id").asText(),
                                "content", result instanceof Map ? toJson(result) : String.valueOf(result)));
                    } else if ("text".equals(block.path("type").asText()) && block.has("text")) {
                        r.answer += block.get("text").asText();
                    }
                }

                if (toolResults.isEmpty()) {
                    r.success = true;
                    break;
                }

                messages.add(Map.of("role", "assistant", "content", assistantContent));
                messages.add(Map.of("role", "user", "content", toolResults));
            }
        } catch (Exception e) {
            r.error = e.getMessage();
        }
        r.latencyMs = System.currentTimeMillis() - t0;
        return r;
    }

    private static ProviderResult testGemini(List<ToolSpec> specs, String summary, String key) {
        List<Map<String, Object>> funcDecls = GeminiAdapter.getGeminiTools(specs);
        ProviderResult r = new ProviderResult("Gemini", "gemini-3.1-flash-lite-preview");

        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", "Stored dataset:\n" + summary + "\n\n" + USER_QUERY))));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent?key=" + key("GOOGLE_API_KEY");
        Map<String, String> headers = Map.of("Content-Type", "application/json");

        long t0 = System.currentTimeMillis();
        try {
            for (int turn = 0; turn < 15; turn++) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("contents", contents);
                body.put("tools", List.of(Map.of("function_declarations", funcDecls)));
                body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM))));

                var resp = post(url, headers, body);
                if (resp.statusCode() != 200) {
                    r.error = "HTTP " + resp.statusCode() + ": " + resp.body();
                    break;
                }

                JsonNode root = parseJson(resp.body());
                JsonNode candidates = root.get("candidates");
                if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                    r.error = "No candidates";
                    break;
                }

                JsonNode contentNode = candidates.get(0).path("content");
                JsonNode parts = contentNode.path("parts");
                boolean hasFc = false;
                List<Map<String, Object>> modelParts = new ArrayList<>();
                List<Map<String, Object>> responseParts = new ArrayList<>();

                for (JsonNode part : parts) {
                    JsonNode fc = part.get("functionCall");
                    if (fc != null) {
                        hasFc = true;
                        r.toolCalls++;
                        String name = fc.get("name").asText();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = fc.has("args") ? parseMap(fc.get("args").toString()) : Map.of();
                        Object result = GeminiAdapter.handleGeminiToolCall(specs, name, args);
                        modelParts.add(parseMap(toJson(part)));
                        responseParts.add(Map.of(
                                "functionResponse", Map.of(
                                        "name", name,
                                        "response", Map.of("result", result))));
                    } else if (part.has("text")) {
                        r.answer += part.get("text").asText();
                        modelParts.add(parseMap(toJson(part)));
                    }
                }

                if (!hasFc) {
                    r.success = true;
                    break;
                }

                contents.add(Map.of("role", "model", "parts", modelParts));
                contents.add(Map.of("role", "user", "parts", responseParts));
            }
        } catch (Exception e) {
            r.error = e.getMessage();
        }
        r.latencyMs = System.currentTimeMillis() - t0;
        return r;
    }

    private static ProviderResult testXai(List<ToolSpec> specs, String summary, String key) {
        List<Map<String, Object>> tools = XaiAdapter.getXaiTools(specs);
        ProviderResult r = new ProviderResult("xAI", "grok-4-1-fast-reasoning");

        List<Object> input = new ArrayList<>();
        input.add(Map.of("role", "user", "content", "Data loaded: " + summary + "\n\n" + USER_QUERY));

        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + key("XAI_API_KEY"),
                "Content-Type", "application/json");

        long t0 = System.currentTimeMillis();
        try {
            for (int turn = 0; turn < 15; turn++) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", "grok-4-1-fast-reasoning");
                body.put("instructions", SYSTEM);
                body.put("input", input);
                body.put("tools", tools);

                var resp = post("https://api.x.ai/v1/responses", headers, body);
                if (resp.statusCode() != 200) {
                    r.error = "HTTP " + resp.statusCode() + ": " + resp.body().substring(0, Math.min(300, resp.body().length()));
                    break;
                }

                JsonNode root = parseJson(resp.body());
                JsonNode output = root.path("output");

                boolean hadToolCall = false;
                for (JsonNode item : output) {
                    input.add(parseMap(item.toString()));
                    String type = item.path("type").asText();
                    if ("function_call".equals(type)) {
                        hadToolCall = true;
                        r.toolCalls++;
                        String name = item.path("name").asText();
                        String arguments = item.path("arguments").asText();
                        Object result = XaiAdapter.handleXaiToolCall(specs, name, arguments);
                        String content = result instanceof Map ? toJson(result) : String.valueOf(result);
                        input.add(Map.of("type", "function_call_output", "call_id", item.path("call_id").asText(), "output", content));
                    } else if ("message".equals(type)) {
                        JsonNode contentArr = item.path("content");
                        for (JsonNode c : contentArr) {
                            if ("output_text".equals(c.path("type").asText()) || "text".equals(c.path("type").asText())) {
                                r.answer += c.path("text").asText();
                            }
                        }
                    }
                }
                String outputText = root.path("output_text").asText("");
                if (!outputText.isBlank() && r.answer.isBlank()) r.answer = outputText;

                if (!hadToolCall) {
                    r.success = !r.answer.isBlank();
                    break;
                }
            }
        } catch (Exception e) {
            r.error = e.getMessage();
        }
        r.latencyMs = System.currentTimeMillis() - t0;
        return r;
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
