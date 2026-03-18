package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;
import com.binomialtechnologies.binomialhash.tokenizers.TokenCounters;

import java.util.List;
import java.util.Map;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Context budget management: track stats, token estimation, compression ratio.
 */
public final class Example13_ContextBudget {

    public static void main(String[] args) {
        printSeparator("Example 13: Context Budget Management");

        BinomialHash bh = new BinomialHash();

        // 1. Ingest market data, print context stats
        printSeparator("After Ingest");
        List<Map<String, Object>> marketData = generateMarketData();
        String rawJson = toJson(marketData);
        bh.ingest(rawJson, "market_data");
        Map<String, Object> stats1 = bh.contextStats();
        System.out.println("Context stats after ingest:");
        stats1.forEach((k, v) -> System.out.println("  " + k + ": " + v));

        // 2. Run several operations
        String key = (String) bh.keys().get(0).get("key");

        printSeparator("After Retrieve");
        bh.retrieve(key);
        Map<String, Object> stats2 = bh.contextStats();
        System.out.println("tool_calls: " + stats2.get("tool_calls"));
        System.out.println("chars_out_to_llm: " + stats2.get("chars_out_to_llm"));

        printSeparator("After Aggregate");
        bh.aggregate(key, "price", "mean");
        bh.aggregate(key, "volume", "sum");
        Map<String, Object> stats3 = bh.contextStats();
        System.out.println("tool_calls: " + stats3.get("tool_calls"));

        printSeparator("After Query");
        bh.query(key, "{\"sector\": \"Tech\"}", "price", true, 10, null);
        Map<String, Object> stats4 = bh.contextStats();
        System.out.println("tool_calls: " + stats4.get("tool_calls"));

        // 3. Token estimation
        printSeparator("Token Estimation");
        int rawTokens = TokenCounters.countTokens(rawJson, "openai");
        System.out.println("Raw JSON tokens (openai): " + rawTokens);
        Object charsOut = stats4.get("chars_out_to_llm");
        long outLong = charsOut instanceof Number ? ((Number) charsOut).longValue() : 0;
        long estTokensOut = outLong / 4;
        System.out.println("Est. tokens to LLM (chars/4): " + estTokensOut);

        // 4. Compression ratio progression
        printSeparator("Compression Ratio");
        double ratio = rawJson.length() / Math.max(outLong, 1.0);
        System.out.println("Overall compression: " + String.format("%.1f", ratio) + "x");
        System.out.println("(raw " + rawJson.length() + " chars -> " + outLong + " chars to LLM)");

        // 5. Summary
        printSeparator("Summary");
        System.out.println("Total raw in:     " + stats4.get("chars_in_raw") + " chars");
        System.out.println("Total to LLM:     " + stats4.get("chars_out_to_llm") + " chars");
        System.out.println("Compression:      " + stats4.get("compression_ratio") + "x");
        System.out.println("Est. tokens out:  " + stats4.get("est_tokens_out"));
        System.out.println("Slots:            " + stats4.get("slots"));
    }
}
