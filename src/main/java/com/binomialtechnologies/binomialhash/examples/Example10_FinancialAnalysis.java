package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;

import java.util.List;
import java.util.Map;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Financial analysis pipeline: factor data ingestion, schema inspection,
 * querying, grouping, and aggregation.
 */
public final class Example10_FinancialAnalysis {

    public static void main(String[] args) {
        printSeparator("Example 10: Financial Analysis Pipeline");

        // 1. Create BH, generate factor data, ingest as JSON
        BinomialHash bh = new BinomialHash();
        List<Map<String, Object>> factorData = generateFactorData();
        String rawJson = toJson(factorData);
        String summary = bh.ingest(rawJson, "factor_data");
        System.out.println("Ingested factor data. Summary:\n" + summary.substring(0, Math.min(400, summary.length())) + "...\n");

        String key = (String) bh.keys().get(0).get("key");

        // 2. Schema inspection: row count, columns, numeric column stats
        printSeparator("Schema Inspection");
        Map<String, Object> schema = bh.schema(key);
        System.out.println("Row count: " + schema.get("row_count"));
        System.out.println("Columns: " + schema.get("columns"));
        @SuppressWarnings("unchecked")
        Map<String, Object> colStats = (Map<String, Object>) schema.get("col_stats");
        if (colStats != null) {
            System.out.println("\nNumeric column stats (sample):");
            for (String col : List.of("return_1m", "sharpe_12m", "price")) {
                if (colStats.containsKey(col)) {
                    System.out.println("  " + col + ": " + colStats.get(col));
                }
            }
        }

        // 3. Query: find stocks with return_1m > 0.01
        printSeparator("Query: return_1m > 0.01");
        Map<String, Object> qResult = bh.query(key, "{\"return_1m\": {\"$gt\": 0.01}}", "return_1m", true, 10, null);
        System.out.println("Matched: " + qResult.get("matched") + ", returned: " + qResult.get("returned"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> qRows = (List<Map<String, Object>>) qResult.get("rows");
        if (qRows != null && !qRows.isEmpty()) {
            System.out.println("Sample rows:");
            for (int i = 0; i < Math.min(3, qRows.size()); i++) {
                Map<String, Object> r = qRows.get(i);
                System.out.println("  " + r.get("ticker") + " | sector=" + r.get("sector") + " | return_1m=" + r.get("return_1m"));
            }
        }

        // 4. Group by sector: aggregate mean return_1m per sector
        printSeparator("Group By Sector: mean return_1m");
        String aggJson = "[{\"column\": \"return_1m\", \"func\": \"mean\", \"alias\": \"avg_return_1m\"}]";
        Map<String, Object> gbResult = bh.groupBy(key, List.of("sector"), aggJson, "avg_return_1m", true, 10);
        System.out.println("Groups: " + gbResult.get("groups"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gbRows = (List<Map<String, Object>>) gbResult.get("rows");
        if (gbRows != null) {
            for (Map<String, Object> r : gbRows) {
                System.out.println("  " + r.get("sector") + ": avg_return_1m = " + r.get("avg_return_1m"));
            }
        }

        // 5. Aggregate: mean, std, min, max of sharpe_12m
        printSeparator("Aggregate sharpe_12m");
        for (String func : List.of("mean", "std", "min", "max")) {
            Map<String, Object> agg = bh.aggregate(key, "sharpe_12m", func);
            System.out.println("  sharpe_12m " + func + ": " + agg.get("result"));
        }

        // 6. Print context budget summary
        printSeparator("Context Budget Summary");
        Map<String, Object> stats = bh.contextStats();
        stats.forEach((k, v) -> System.out.println("  " + k + ": " + v));
    }
}
