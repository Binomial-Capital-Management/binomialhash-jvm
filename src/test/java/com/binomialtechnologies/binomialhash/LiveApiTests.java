package com.binomialtechnologies.binomialhash;

import com.binomialtechnologies.binomialhash.adapters.*;
import com.binomialtechnologies.binomialhash.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live API integration tests for each LLM provider adapter.
 *
 * These tests make REAL HTTP calls. They are skipped automatically when the
 * corresponding API key is not available.
 *
 * Keys are loaded from (checked in order):
 *   1. Environment variables (OPENAI_API_KEY, ANTHROPIC_API_KEY, GOOGLE_API_KEY, XAI_API_KEY)
 *   2. A .env file in the project root (java/.env)
 *
 * Copy java/.env.example to java/.env and fill in your keys.
 */
class LiveApiTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final Map<String, String> KEYS = new HashMap<>();
    private static ToolSpec weatherTool;
    private static List<ToolSpec> specs;

    @BeforeAll
    static void setUp() {
        loadDotEnv();

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "city", Map.of("type", "string", "description", "The city name")));
        schema.put("required", List.of("city"));

        weatherTool = new ToolSpec("get_weather", "Get the current weather for a city", schema,
                args -> Map.of("city", args.get("city"), "temp_f", 72, "condition", "sunny"));
        specs = List.of(weatherTool);
    }

    private static void loadDotEnv() {
        for (String name : List.of("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GOOGLE_API_KEY", "XAI_API_KEY")) {
            String val = System.getenv(name);
            if (val != null && !val.isBlank()) KEYS.put(name, val);
        }
        for (Path p : List.of(Path.of(".env"), Path.of("java/.env"), Path.of("../.env"))) {
            if (Files.isRegularFile(p)) {
                try {
                    for (String line : Files.readAllLines(p)) {
                        line = line.strip();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            String k = line.substring(0, eq).strip();
                            String v = line.substring(eq + 1).strip();
                            if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                            if (!v.isBlank() && !KEYS.containsKey(k)) KEYS.put(k, v);
                        }
                    }
                } catch (IOException ignored) {}
                break;
            }
        }
    }

    private static String key(String name) {
        return KEYS.get(name);
    }

    // ═══════════════════════════════════════════════════════════════════
    // OpenAI — Responses API
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testOpenAiLiveToolCall() throws Exception {
        String apiKey = key("OPENAI_API_KEY");
        assumeTrue(apiKey != null, "OPENAI_API_KEY not set — skipping");

        List<Map<String, Object>> tools = OpenAiAdapter.getOpenAiTools(specs, false, "responses");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4.1-nano");
        body.put("input", "What is the weather in San Francisco?");
        body.put("tools", tools);

        String json = MAPPER.writeValueAsString(body);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/responses"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "OpenAI response status: " + resp.body());

        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode output = root.get("output");
        assertNotNull(output, "Response should have 'output'");
        assertTrue(output.isArray() && output.size() > 0, "Output should be non-empty");

        boolean foundToolCall = false;
        for (JsonNode item : output) {
            if ("function_call".equals(item.path("type").asText())) {
                foundToolCall = true;
                String name = item.get("name").asText();
                assertEquals("get_weather", name);
                String argsJson = item.get("arguments").asText();
                Map<String, Object> args = AdapterCommon.parseArguments(argsJson);
                assertNotNull(args.get("city"), "Should include city argument");

                Object result = OpenAiAdapter.handleOpenAiToolCall(specs, name, argsJson);
                assertNotNull(result, "Tool handler should return a result");
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = (Map<String, Object>) result;
                assertEquals(72, resultMap.get("temp_f"));
                break;
            }
        }
        assertTrue(foundToolCall, "OpenAI should have made a function_call to get_weather");
    }

    // ═══════════════════════════════════════════════════════════════════
    // OpenAI — Chat Completions API (legacy)
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testOpenAiChatCompletionsLive() throws Exception {
        String apiKey = key("OPENAI_API_KEY");
        assumeTrue(apiKey != null, "OPENAI_API_KEY not set — skipping");

        List<Map<String, Object>> tools = OpenAiAdapter.getOpenAiTools(specs, false, "chat_completions");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4.1-nano");
        body.put("messages", List.of(Map.of("role", "user", "content", "What is the weather in Tokyo?")));
        body.put("tools", tools);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Chat completions status: " + resp.body());

        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode choice = root.path("choices").get(0);
        JsonNode msg = choice.get("message");
        JsonNode toolCalls = msg.get("tool_calls");
        assertNotNull(toolCalls, "Should have tool_calls in message");
        assertTrue(toolCalls.isArray() && toolCalls.size() > 0);

        JsonNode tc = toolCalls.get(0);
        String name = tc.path("function").path("name").asText();
        assertEquals("get_weather", name);

        String argsJson = tc.path("function").path("arguments").asText();
        Object result = OpenAiAdapter.handleOpenAiToolCall(specs, name, argsJson);
        assertNotNull(result);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Anthropic — Messages API
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testAnthropicLiveToolCall() throws Exception {
        String apiKey = key("ANTHROPIC_API_KEY");
        assumeTrue(apiKey != null, "ANTHROPIC_API_KEY not set — skipping");

        List<Map<String, Object>> tools = AnthropicAdapter.getAnthropicTools(specs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "claude-sonnet-4-20250514");
        body.put("max_tokens", 1024);
        body.put("messages", List.of(Map.of("role", "user", "content", "What's the weather in London?")));
        body.put("tools", tools);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Anthropic status: " + resp.body());

        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode content = root.get("content");
        assertNotNull(content);

        boolean foundToolUse = false;
        for (JsonNode block : content) {
            if ("tool_use".equals(block.path("type").asText())) {
                foundToolUse = true;
                String name = block.get("name").asText();
                assertEquals("get_weather", name);

                @SuppressWarnings("unchecked")
                Map<String, Object> input = MAPPER.convertValue(block.get("input"), Map.class);
                Object result = AnthropicAdapter.handleAnthropicToolUse(specs, name, input);
                assertNotNull(result);
                break;
            }
        }
        assertTrue(foundToolUse, "Anthropic should return a tool_use content block");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Google Gemini — generateContent
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testGeminiLiveToolCall() throws Exception {
        String apiKey = key("GOOGLE_API_KEY");
        assumeTrue(apiKey != null, "GOOGLE_API_KEY not set — skipping");

        List<Map<String, Object>> funcDecls = GeminiAdapter.getGeminiTools(specs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", "What's the weather in Paris?")))));
        body.put("tools", List.of(Map.of("function_declarations", funcDecls)));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + apiKey;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Gemini status: " + resp.body());

        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode candidates = root.get("candidates");
        assertNotNull(candidates);
        JsonNode parts = candidates.get(0).path("content").path("parts");

        boolean foundFunctionCall = false;
        for (JsonNode part : parts) {
            JsonNode fc = part.get("functionCall");
            if (fc != null) {
                foundFunctionCall = true;
                String name = fc.get("name").asText();
                assertEquals("get_weather", name);

                @SuppressWarnings("unchecked")
                Map<String, Object> args = MAPPER.convertValue(fc.get("args"), Map.class);
                Object result = GeminiAdapter.handleGeminiToolCall(specs, name, args);
                assertNotNull(result);
                break;
            }
        }
        assertTrue(foundFunctionCall, "Gemini should return a functionCall part");
    }

    // ═══════════════════════════════════════════════════════════════════
    // xAI / Grok — OpenAI-compatible
    // ═══════════════════════════════════════════════════════════════════
    @Test
    void testXaiLiveToolCall() throws Exception {
        String apiKey = key("XAI_API_KEY");
        assumeTrue(apiKey != null, "XAI_API_KEY not set — skipping");

        List<Map<String, Object>> tools = XaiAdapter.getXaiTools(specs);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "grok-3-mini-fast");
        body.put("messages", List.of(Map.of("role", "user", "content", "What is the weather in Berlin?")));
        body.put("tools", tools);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.x.ai/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "xAI status: " + resp.body());

        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode choice = root.path("choices").get(0);
        JsonNode toolCalls = choice.path("message").get("tool_calls");
        assertNotNull(toolCalls, "xAI should return tool_calls");
        assertTrue(toolCalls.isArray() && toolCalls.size() > 0);

        JsonNode tc = toolCalls.get(0);
        String name = tc.path("function").path("name").asText();
        assertEquals("get_weather", name);

        String argsJson = tc.path("function").path("arguments").asText();
        Object result = XaiAdapter.handleXaiToolCall(specs, name, argsJson);
        assertNotNull(result);
    }
}
