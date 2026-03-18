package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;

import java.util.List;
import java.util.Map;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Manifold features: schema, query slices, group by, aggregate, multi-dimensional views.
 */
public final class Example14_ManifoldDeepDive {

    public static void main(String[] args) {
        printSeparator("Example 14: Manifold Deep Dive");

        // 1. Create BH, ingest factor data
        BinomialHash bh = new BinomialHash();
        List<Map<String, Object>> factorData = generateFactorData();
        bh.ingest(toJson(factorData), "factor_manifold");
        String key = (String) bh.keys().get(0).get("key");

        // 2. Schema and column summary
        printSeparator("Schema & Column Summary");
        Map<String, Object> schema = bh.schema(key);
        System.out.println("Row count: " + schema.get("row_count"));
        System.out.println("Columns: " + schema.get("columns"));
        @SuppressWarnings("unchecked")
        Map<String, String> colTypes = (Map<String, String>) schema.get("col_types");
        if (colTypes != null) {
            System.out.println("Column types: " + colTypes);
        }

        // 3. Query various slices
        printSeparator("Query Slices");

        Map<String, Object> slice1 = bh.query(key, "{\"sector\": \"Tech\"}", "return_1m", true, 5, null);
        System.out.println("Tech sector (return_1m desc): matched=" + slice1.get("matched"));

        Map<String, Object> slice2 = bh.query(key, "{\"return_1m\": {\"$gt\": 0.02}}", "sharpe_12m", true, 5, null);
        System.out.println("return_1m > 0.02: matched=" + slice2.get("matched"));

        Map<String, Object> slice3 = bh.query(key, "{\"month\": {\"$lte\": 6}}", "price", false, 5, null);
        System.out.println("month <= 6: matched=" + slice3.get("matched"));

        // 4. Group by sector and month (pivot-like view)
        printSeparator("Group By Sector & Month");
        String aggJson = "[{\"column\": \"return_1m\", \"func\": \"mean\", \"alias\": \"avg_return\"}, "
                + "{\"column\": \"sharpe_12m\", \"func\": \"mean\", \"alias\": \"avg_sharpe\"}]";
        Map<String, Object> gbResult = bh.groupBy(key, List.of("sector", "month"), aggJson, "avg_return", true, 15);
        System.out.println("Groups: " + gbResult.get("groups"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gbRows = (List<Map<String, Object>>) gbResult.get("rows");
        if (gbRows != null && !gbRows.isEmpty()) {
            System.out.println("Sample pivot rows:");
            for (int i = 0; i < Math.min(5, gbRows.size()); i++) {
                Map<String, Object> r = gbRows.get(i);
                System.out.println("  sector=" + r.get("sector") + " month=" + r.get("month")
                        + " avg_return=" + r.get("avg_return") + " avg_sharpe=" + r.get("avg_sharpe"));
            }
        }

        // 5. Aggregate multiple fields
        printSeparator("Multi-Field Aggregates");
        for (String col : List.of("return_1m", "sharpe_12m", "beta")) {
            Map<String, Object> mean = bh.aggregate(key, col, "mean");
            Map<String, Object> std = bh.aggregate(key, col, "std");
            System.out.println(col + ": mean=" + mean.get("result") + ", std=" + std.get("result"));
        }

        // 6. Same data, different slices (no re-fetch)
        printSeparator("Same Data, Different Slices");
        Map<String, Object> r1 = bh.retrieve(key, 0, 3, "price", true, List.of("ticker", "sector", "price"));
        Map<String, Object> r2 = bh.retrieve(key, 10, 3, "return_1m", false, List.of("ticker", "return_1m"));
        System.out.println("Slice 1 (price desc): " + r1.get("returned") + " rows");
        System.out.println("Slice 2 (return_1m asc): " + r2.get("returned") + " rows");
        System.out.println("Both from same cached slot (no re-fetch).");

        // 7. Final context stats
        printSeparator("Final Context Stats");
        Map<String, Object> stats = bh.contextStats();
        stats.forEach((k, v) -> System.out.println("  " + k + ": " + v));
    }
}
