package com.binomialtechnologies.binomialhash;

import com.binomialtechnologies.binomialhash.adapters.*;
import com.binomialtechnologies.binomialhash.context.BinomialHashContext;
import com.binomialtechnologies.binomialhash.exporters.*;
import com.binomialtechnologies.binomialhash.insights.InsightEngine;
import com.binomialtechnologies.binomialhash.manifold.*;
import com.binomialtechnologies.binomialhash.stats.*;
import com.binomialtechnologies.binomialhash.tokenizers.*;
import com.binomialtechnologies.binomialhash.tools.ToolSpec;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 20 JUnit 5 tests covering all major modules of the BinomialHash Java port.
 */
class BinomialHashTests {

    // ═════════════════════════════════════════════════════════════════════
    // 1. StatsHelpers — toFloatPermissive
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testToFloatPermissive() {
        assertEquals(3.14, StatsHelpers.toFloatPermissive(3.14));
        assertEquals(42.0, StatsHelpers.toFloatPermissive("42"));
        assertEquals(1000.0, StatsHelpers.toFloatPermissive("$1,000"));
        assertNull(StatsHelpers.toFloatPermissive(null));
        assertNull(StatsHelpers.toFloatPermissive(""));
        assertNull(StatsHelpers.toFloatPermissive(true));
        assertNull(StatsHelpers.toFloatPermissive(Double.NaN));
        assertNull(StatsHelpers.toFloatPermissive(Double.POSITIVE_INFINITY));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. StatsHelpers — pearsonCorr
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testPearsonCorrelation() {
        double[] xs = {1, 2, 3, 4, 5};
        double[] ys = {2, 4, 6, 8, 10};
        double corr = StatsHelpers.pearsonCorr(xs, ys);
        assertEquals(1.0, corr, 1e-6, "Perfect positive correlation");

        double[] neg = {10, 8, 6, 4, 2};
        assertEquals(-1.0, StatsHelpers.pearsonCorr(xs, neg), 1e-6, "Perfect negative correlation");

        assertEquals(0.0, StatsHelpers.pearsonCorr(new double[]{1, 2}, new double[]{3, 4}), 1e-6,
                "Too few samples returns 0");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. StatsHelpers — fitLinear
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testFitLinear() {
        double[] xs = {0, 1, 2, 3, 4};
        double[] ys = {1, 3, 5, 7, 9};
        double[] fit = StatsHelpers.fitLinear(xs, ys);
        assertEquals(2.0, fit[0], 1e-6, "slope = 2");
        assertEquals(1.0, fit[1], 1e-6, "intercept = 1");
        assertEquals(1.0, fit[2], 1e-6, "R² = 1 (perfect fit)");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. StatsHelpers — aggregation functions
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testAggregation() {
        List<Double> vals = List.of(10.0, 20.0, 30.0, 40.0, 50.0);
        assertEquals(150.0, StatsHelpers.aggNumeric(vals, "sum"));
        assertEquals(30.0, StatsHelpers.aggNumeric(vals, "mean"));
        assertEquals(30.0, StatsHelpers.aggNumeric(vals, "median"));
        assertEquals(10.0, StatsHelpers.aggNumeric(vals, "min"));
        assertEquals(50.0, StatsHelpers.aggNumeric(vals, "max"));
        double std = StatsHelpers.aggNumeric(vals, "std");
        assertTrue(Math.abs(std - 14.1421) < 0.01, "Standard deviation ~14.14");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. StatsHelpers — quantileEdges and bucketIndex
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testQuantileEdgesAndBucket() {
        double[] vals = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] edges = StatsHelpers.quantileEdges(vals, 4);
        assertEquals(5, edges.length, "4 bins → 5 edges");
        assertEquals(1.0, edges[0], 1e-6);
        assertEquals(10.0, edges[4], 1e-6);

        assertEquals(0, StatsHelpers.bucketIndex(1.0, edges));
        assertEquals(3, StatsHelpers.bucketIndex(10.0, edges));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. TokenCounters — fallback and OpenAI
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testTokenCounters() {
        String text = "Hello, world!";
        int fallback = FallbackCounter.charsFallback(text);
        assertEquals((text.length() + 3) / 4, fallback, "Fallback = ceil(len/4)");

        int openai = TokenCounters.countTokens(text, "openai");
        assertTrue(openai > 0, "OpenAI tokenizer returns positive count");
        assertFalse(TokenCounters.isExact("anthropic"), "Anthropic uses fallback");
        assertThrows(IllegalArgumentException.class,
                () -> TokenCounters.countTokens(text, "nonexistent"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. CsvExporter
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testCsvExporter() {
        List<Map<String, Object>> rows = List.of(
                Map.of("name", "Alice", "age", 30),
                Map.of("name", "Bob", "age", 25));
        List<String> cols = List.of("name", "age");
        Map<String, String> types = Map.of("name", "text", "age", "numeric");

        String csv = CsvExporter.exportCsv(rows, cols, types);
        assertTrue(csv.contains("name,age"), "Header present");
        assertTrue(csv.contains("Alice,30"), "First data row");
        assertTrue(csv.contains("Bob,25"), "Second data row");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. MarkdownExporter
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testMarkdownExporter() {
        List<Map<String, Object>> rows = List.of(
                Map.of("city", "NYC", "pop", 8000000),
                Map.of("city", "LA", "pop", 4000000));
        List<String> cols = List.of("city", "pop");
        Map<String, String> types = Map.of("city", "text", "pop", "numeric");

        String md = MarkdownExporter.exportMarkdown(rows, cols, types);
        assertTrue(md.contains("| city | pop |"), "Header row");
        assertTrue(md.contains("| NYC | 8000000 |"), "NYC row");
        assertTrue(md.contains("---:"), "Numeric alignment marker");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. ToolSpec construction
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testToolSpec() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string")));
        ToolSpec spec = new ToolSpec("my_tool", "A test tool", schema, args -> "ok", "test_group");
        assertEquals("my_tool", spec.getName());
        assertEquals("A test tool", spec.getDescription());
        assertEquals("test_group", spec.getGroup());
        assertEquals("ok", spec.getHandler().apply(Map.of()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. OpenAiAdapter — Responses API format
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testOpenAiAdapterResponsesFormat() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query"));
        ToolSpec spec = new ToolSpec("bh_query", "Run a query", schema, args -> "result");

        List<Map<String, Object>> tools = OpenAiAdapter.getOpenAiTools(List.of(spec));
        assertEquals(1, tools.size());
        Map<String, Object> tool = tools.get(0);
        assertEquals("function", tool.get("type"));
        assertEquals("bh_query", tool.get("name"));
        assertNotNull(tool.get("parameters"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. OpenAiAdapter — strict mode
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testOpenAiAdapterStrictMode() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("q", Map.of("type", "string")));
        ToolSpec spec = new ToolSpec("bh_search", "Search", schema, args -> null);

        List<Map<String, Object>> tools = OpenAiAdapter.getOpenAiTools(List.of(spec), true, "responses");
        Map<String, Object> tool = tools.get(0);
        assertEquals(true, tool.get("strict"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) tool.get("parameters");
        assertEquals(false, params.get("additionalProperties"));
        assertTrue(params.containsKey("required"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. AdapterCommon — parseArguments and handleToolCall
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testAdapterCommonParseAndHandle() {
        Map<String, Object> mapArgs = Map.of("key", "value");
        assertEquals(mapArgs, AdapterCommon.parseArguments(mapArgs));

        Map<String, Object> fromJson = AdapterCommon.parseArguments("{\"a\":1}");
        assertEquals(1, fromJson.get("a"));

        assertTrue(AdapterCommon.parseArguments(null).isEmpty());

        ToolSpec spec = new ToolSpec("echo", "echo", Map.of(), args -> args.get("msg"));
        Object result = AdapterCommon.handleToolCall(List.of(spec), "echo", Map.of("msg", "hi"));
        assertEquals("hi", result);

        assertThrows(IllegalArgumentException.class,
                () -> AdapterCommon.handleToolCall(List.of(spec), "unknown", Map.of()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. BinomialHashContext — thread-local lifecycle
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testBinomialHashContext() {
        BinomialHashContext.setBhFactory(() -> "mock_bh_instance");

        Object bh = BinomialHashContext.initBinomialHash();
        assertEquals("mock_bh_instance", bh);
        assertEquals("mock_bh_instance", BinomialHashContext.getBinomialHash());

        assertFalse(BinomialHashContext.isRawMode());
        BinomialHashContext.enterRawMode();
        assertTrue(BinomialHashContext.isRawMode());
        BinomialHashContext.enterRawMode();
        assertTrue(BinomialHashContext.isRawMode());
        BinomialHashContext.exitRawMode();
        assertTrue(BinomialHashContext.isRawMode(), "Still nested");
        BinomialHashContext.exitRawMode();
        assertFalse(BinomialHashContext.isRawMode());

        BinomialHashContext.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. BinomialHashContext — withRawMode
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testWithRawMode() {
        assertFalse(BinomialHashContext.isRawMode());
        String result = BinomialHashContext.withRawMode(() -> {
            assertTrue(BinomialHashContext.isRawMode());
            return "inside";
        });
        assertEquals("inside", result);
        assertFalse(BinomialHashContext.isRawMode());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 15. ManifoldAxis and GridPoint data structures
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testManifoldStructures() {
        ManifoldAxis axis = new ManifoldAxis("region", List.of("US", "EU"), false, "categorical", 2);
        assertEquals("region", axis.getColumn());
        assertFalse(axis.isOrdered());
        assertEquals(2, axis.getSize());
        assertFalse(axis.isWraps());
        axis.setWraps(true);
        assertTrue(axis.isWraps());

        Map<String, Object> m = axis.toMap();
        assertEquals("region", m.get("column"));
        assertEquals(true, m.get("wraps"));

        GridPoint gp = new GridPoint(0, List.of("US"), new LinkedHashMap<>(Map.of("revenue", 100.0)));
        assertEquals(0, gp.getIndex());
        assertEquals(100.0, gp.getFieldValues().get("revenue"));
        gp.setCurvature(0.5);
        assertEquals(0.5, gp.getCurvature());
        gp.getNeighbors().add(1);
        assertEquals(List.of(1), gp.getNeighbors());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 16. InsightEngine — discoverBestDriver
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testDiscoverBestDriver() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("x", (double) i);
            row.put("noise", Math.random() * 100);
            row.put("y", 2.0 * i + 1.0);
            rows.add(row);
        }
        Map<String, Object> result = InsightEngine.discoverBestDriver(rows, "y", List.of("x", "noise"), 5);
        assertNotNull(result);
        assertEquals("x", result.get("driver"), "x is the perfect driver of y");
        double r2 = (double) result.get("r2");
        assertTrue(r2 > 0.99, "R² should be ~1.0");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 17. RegressionStats — regressDataset
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testRegressDataset() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Random regRng = new Random(99);
        for (int i = 0; i < 30; i++) {
            double x1 = i; double x2 = regRng.nextGaussian() * 5;
            double y = 3 * x1 + 2 * x2 + 10 + regRng.nextGaussian() * 0.1;
            rows.add(Map.of("x1", x1, "x2", x2, "y", y));
        }
        Map<String, String> colTypes = Map.of("x1", "numeric", "x2", "numeric", "y", "numeric");
        Map<String, Object> result = RegressionStats.regressDataset(
                rows, List.of("x1", "x2", "y"), colTypes, "y", List.of("x1", "x2"), null);
        assertFalse(result.containsKey("error"), "No error: " + result);
        double r2 = (double) result.get("r2");
        assertTrue(r2 > 0.9, "Strong linear relationship");
        assertEquals(30, result.get("samples"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 18. DynamicsStats — autocorrelationDataset
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testAutocorrelation() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(Map.of("t", (double) i, "val", Math.sin(2 * Math.PI * i / 10.0)));
        }
        Map<String, String> colTypes = Map.of("t", "numeric", "val", "numeric");
        Map<String, Object> result = DynamicsStats.autocorrelationDataset(rows, colTypes, "val", "t", 20, null);
        assertFalse(result.containsKey("error"), "No error");
        assertEquals(100, result.get("samples"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> acfVals = (List<Map<String, Object>>) result.get("acf_values");
        assertFalse(acfVals.isEmpty());
        Integer domPeriod = (Integer) result.get("dominant_period");
        assertNotNull(domPeriod, "Should detect a dominant period in a sine wave");
        assertTrue(domPeriod >= 9 && domPeriod <= 11, "Period ~10");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 19. StructureStats — clusterDataset (k-means)
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testClusterDataset() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Random rng = new Random(42);
        for (int i = 0; i < 50; i++) {
            rows.add(Map.of("a", rng.nextGaussian(), "b", rng.nextGaussian()));
        }
        for (int i = 0; i < 50; i++) {
            rows.add(Map.of("a", 10 + rng.nextGaussian(), "b", 10 + rng.nextGaussian()));
        }
        Map<String, String> colTypes = Map.of("a", "numeric", "b", "numeric");
        Map<String, Object> result = StructureStats.clusterDataset(
                rows, colTypes, List.of("a", "b"), 2, null, null);
        assertFalse(result.containsKey("error"), "No error: " + result);
        assertEquals(2, result.get("k"));
        double sil = (double) result.get("silhouette_score");
        assertTrue(sil > 0.5, "Two well-separated clusters should have good silhouette");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clusters = (List<Map<String, Object>>) result.get("clusters");
        assertEquals(2, clusters.size());
    }

    // ═════════════════════════════════════════════════════════════════════
    // 20. LawsStats — symmetryScanDataset
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testSymmetryScan() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Random rng = new Random(0);
        for (int i = 0; i < 100; i++) {
            double v = rng.nextGaussian();
            rows.add(Map.of("x", v, "y", v + rng.nextGaussian() * 0.01));
        }
        Map<String, String> colTypes = Map.of("x", "numeric", "y", "numeric");
        Map<String, Object> result = LawsStats.symmetryScanDataset(rows, colTypes, List.of("x", "y"), null);
        assertFalse(result.containsKey("error"), "No error");
        assertEquals(100, result.get("samples"));
        int totalSyms = (int) result.get("total_symmetries_found");
        assertTrue(totalSyms >= 1, "Should find at least reflection or permutation symmetry");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 21. BinomialHash — full ingest / retrieve / aggregate / query / groupBy / schema
    // ═════════════════════════════════════════════════════════════════════
    @Test
    @SuppressWarnings("unchecked")
    void testBinomialHashCoreFlow() throws Exception {
        BinomialHash bh = new BinomialHash();

        // Build a JSON payload larger than INGEST_THRESHOLD_CHARS (3000)
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("name", "item_" + i);
            row.put("region", i % 3 == 0 ? "East" : i % 3 == 1 ? "West" : "North");
            row.put("value", 10.0 + i * 0.5);
            row.put("score", 100 - i * 0.3);
            data.add(row);
        }
        String rawJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data);
        assertTrue(rawJson.length() > BinomialHash.INGEST_THRESHOLD_CHARS, "Payload should exceed threshold");

        // Ingest
        String summary = bh.ingest(rawJson, "test_data");
        assertTrue(summary.contains("[BH]"), "Summary should start with [BH] marker");
        assertTrue(summary.contains("200 records"), "Summary should report 200 records");

        // Extract key from the summary
        String key = summary.split("key=\"")[1].split("\"")[0];
        assertFalse(key.isEmpty(), "Key should be extracted from summary");

        // Keys
        List<Map<String, Object>> keys = bh.keys();
        assertEquals(1, keys.size());
        assertEquals(key, keys.get(0).get("key"));

        // Retrieve
        Map<String, Object> retrieved = bh.retrieve(key, 0, 10, "value", true, null);
        assertFalse(retrieved.containsKey("error"), "Retrieve should not error: " + retrieved);
        assertEquals(200, retrieved.get("total_rows"));
        List<Map<String, Object>> rRows = (List<Map<String, Object>>) retrieved.get("rows");
        assertEquals(10, rRows.size());

        // Aggregate
        Map<String, Object> aggResult = bh.aggregate(key, "value", "mean");
        assertFalse(aggResult.containsKey("error"), "Aggregate should not error");
        assertNotNull(aggResult.get("result"));

        // Schema
        Map<String, Object> schemaResult = bh.schema(key);
        assertFalse(schemaResult.containsKey("error"), "Schema should not error");
        Map<String, String> colTypes = (Map<String, String>) schemaResult.get("col_types");
        assertEquals("numeric", colTypes.get("value"));

        // Query
        Map<String, Object> queryResult = bh.query(key,
                "{\"column\":\"value\",\"op\":\">\",\"value\":50}", null, true, 25, null);
        assertFalse(queryResult.containsKey("error"), "Query should not error: " + queryResult);
        int matched = (int) queryResult.get("matched");
        assertTrue(matched > 0 && matched < 200, "Query should filter some rows");

        // Group by
        Map<String, Object> groupResult = bh.groupBy(key,
                List.of("region"),
                "[{\"column\":\"value\",\"func\":\"mean\",\"alias\":\"avg_value\"}]",
                "avg_value", true, 50);
        assertFalse(groupResult.containsKey("error"), "GroupBy should not error: " + groupResult);
        assertEquals(3, groupResult.get("groups"));

        // Deduplicate: ingest same payload again, should get same key
        String summary2 = bh.ingest(rawJson, "test_data");
        assertTrue(summary2.contains(key), "Duplicate ingest should return same key");
        assertEquals(1, bh.keys().size(), "Should still be 1 slot after dedup");

        // Context stats
        Map<String, Object> cs = bh.contextStats();
        assertTrue((int) cs.get("tool_calls") > 0);
        assertTrue((long) cs.get("chars_in_raw") > 0);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 22. BinomialHash — small payload passthrough
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testBinomialHashSmallPayload() {
        BinomialHash bh = new BinomialHash();
        String small = "{\"key\": \"value\"}";
        String result = bh.ingest(small, "tiny");
        assertEquals(small, result, "Small payloads should pass through unchanged");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 23. BinomialHash — fingerprint and makeKey
    // ═════════════════════════════════════════════════════════════════════
    @Test
    void testFingerprintAndMakeKey() {
        String fp = BinomialHash.fingerprint("hello world");
        assertEquals(64, fp.length(), "SHA-256 hex should be 64 chars");
        assertEquals(fp, BinomialHash.fingerprint("hello world"), "Same input same fingerprint");
        assertNotEquals(fp, BinomialHash.fingerprint("hello world!"), "Different input different fingerprint");

        String key = BinomialHash.makeKey("My Test Label!", fp, 20);
        assertTrue(key.startsWith("my_test_label_"), "Key should be cleaned lowercase");
        assertTrue(key.endsWith("_" + fp.substring(0, 6)), "Key should end with fp prefix");
    }
}
