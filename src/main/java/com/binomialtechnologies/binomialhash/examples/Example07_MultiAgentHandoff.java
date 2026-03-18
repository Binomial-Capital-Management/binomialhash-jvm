package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;

import java.util.List;
import java.util.Map;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Multi-agent handoff with shared BH state.
 * Data Fetcher Agent ingests data; Analyst Agent analyzes via retrieve/aggregate/query.
 */
public class Example07_MultiAgentHandoff {

    public static void main(String[] args) {
        BinomialHash bh = new BinomialHash();

        printSeparator("Data Fetcher Agent");
        String marketJson = toJson(generateMarketData());
        String summary = bh.ingest(marketJson, "market_data");
        System.out.println("Ingested market data. Summary:\n" + summary);

        List<Map<String, Object>> keys = bh.keys();
        String slotKey = keys.isEmpty() ? null : (String) keys.get(0).get("key");
        if (slotKey == null) {
            System.out.println("No slots available.");
            return;
        }

        printSeparator("Analyst Agent");
        System.out.println("Analyst queries BH (shared instance):");

        Map<String, Object> retrieved = bh.retrieve(slotKey, 0, 5, "price", true, null);
        System.out.println("Retrieve (top 5 by price): " + toJson(retrieved).substring(0, Math.min(200, toJson(retrieved).length())) + "...");

        Map<String, Object> agg = bh.aggregate(slotKey, "price", "avg");
        System.out.println("Aggregate (avg price): " + toJson(agg));

        Map<String, Object> queryResult = bh.query(slotKey, "{\"sector\":\"Tech\"}", "volume", true, 3, null);
        System.out.println("Query (sector=Tech, top 3 by volume): " + toJson(queryResult).substring(0, Math.min(150, toJson(queryResult).length())) + "...");

        printSeparator("Context Stats (shared across agents)");
        System.out.println(toJson(bh.contextStats()));
    }
}
