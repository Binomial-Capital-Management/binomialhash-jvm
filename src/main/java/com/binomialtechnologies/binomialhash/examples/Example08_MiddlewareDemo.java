package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;
import com.binomialtechnologies.binomialhash.middleware.BinomialHashMiddleware;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Demonstrates the middleware concept (auto-ingest pattern).
 * Tool output → bh.ingest() → compact summary to LLM. Raw mode bypass for small outputs.
 */
public class Example08_MiddlewareDemo {

    public static void main(String[] args) {
        BinomialHash bh = new BinomialHash();

        printSeparator("Simulated tool: large JSON output");
        String largeOutput = simulatedToolCall("fetch_market_data");
        System.out.println("Tool returned: " + largeOutput.length() + " chars");

        printSeparator("Pattern: tool output → bh.ingest() → compact summary");
        String summary = bh.ingest(largeOutput, "market_data");
        System.out.println("Summary to LLM: " + summary.length() + " chars");
        System.out.println(summary.substring(0, Math.min(300, summary.length())) + "...");

        printSeparator("Raw mode bypass: small output passes through unchanged");
        String smallOutput = "{\"status\":\"ok\",\"count\":1}";
        String smallResult = bh.ingest(smallOutput, "status");
        System.out.println("Small output (" + smallOutput.length() + " chars) → " + smallResult.length() + " chars (unchanged)");
        System.out.println("Result: " + smallResult);

        printSeparator("Multiple tool calls ingested");
        bh.ingest(toJson(generateEarningsData("AAPL")), "earnings_aapl");
        bh.ingest(toJson(generateFactorData()), "factor_data");

        printSeparator("Final context stats and keys");
        System.out.println("Stats: " + toJson(bh.contextStats()));
        System.out.println("Keys: " + toJson(bh.keys()));

        printSeparator("Middleware wrap pattern");
        Function<String, Object> wrapped = BinomialHashMiddleware.wrapToolWithBh(
                Example08_MiddlewareDemo::simulatedToolCall, "wrapped_tool", bh, 3000);
        Object wrappedResult = wrapped.apply("fetch");
        System.out.println("Wrapped tool result length: " + (wrappedResult instanceof String ? ((String) wrappedResult).length() : "N/A"));
    }

    private static String simulatedToolCall(String action) {
        return toJson(generateMarketData());
    }
}
