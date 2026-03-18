package com.binomialtechnologies.binomialhash.examples;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Shared utilities for all BinomialHash Java examples.
 */
public final class ExampleUtils {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    private static final Map<String, String> KEYS = new HashMap<>();

    static {
        loadDotEnv();
    }

    private ExampleUtils() {}

    private static void loadDotEnv() {
        for (String name : List.of("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GOOGLE_API_KEY", "GEMINI_API_KEY", "XAI_API_KEY")) {
            String val = System.getenv(name);
            if (val != null && !val.isBlank()) KEYS.put(name, val);
        }
        if (!KEYS.containsKey("GOOGLE_API_KEY") && KEYS.containsKey("GEMINI_API_KEY"))
            KEYS.put("GOOGLE_API_KEY", KEYS.get("GEMINI_API_KEY"));
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
        if (!KEYS.containsKey("GOOGLE_API_KEY") && KEYS.containsKey("GEMINI_API_KEY"))
            KEYS.put("GOOGLE_API_KEY", KEYS.get("GEMINI_API_KEY"));
    }

    public static String key(String name) {
        return KEYS.get(name);
    }

    public static boolean hasKey(String name) {
        String k = KEYS.get(name);
        return k != null && !k.isBlank();
    }

    public static String toJson(Object obj) {
        try { return MAPPER.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    public static JsonNode parseJson(String json) {
        try { return MAPPER.readTree(json); }
        catch (JsonProcessingException e) { return MAPPER.createObjectNode(); }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String json) {
        try { return MAPPER.readValue(json, Map.class); }
        catch (JsonProcessingException e) { return Map.of(); }
    }

    public static HttpResponse<String> post(String url, Map<String, String> headers, Object body)
            throws IOException, InterruptedException {
        var rb = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)));
        headers.forEach(rb::header);
        return HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Generate synthetic stock market data. */
    public static List<Map<String, Object>> generateMarketData() {
        Random rng = new Random(42);
        String[] tickers = {"AAPL", "MSFT", "GOOGL", "AMZN", "META", "NVDA", "TSLA", "JPM", "GS", "JNJ", "XOM", "CVX"};
        Map<String, String> sectors = Map.ofEntries(
                Map.entry("AAPL", "Tech"), Map.entry("MSFT", "Tech"), Map.entry("GOOGL", "Tech"),
                Map.entry("AMZN", "Tech"), Map.entry("META", "Tech"), Map.entry("NVDA", "Tech"),
                Map.entry("TSLA", "Tech"), Map.entry("JPM", "Finance"), Map.entry("GS", "Finance"),
                Map.entry("JNJ", "Health"), Map.entry("XOM", "Energy"), Map.entry("CVX", "Energy"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String t : tickers) {
            for (int d = 1; d <= 20; d++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ticker", t);
                row.put("sector", sectors.get(t));
                row.put("date", String.format("2025-03-%02d", d));
                row.put("price", Math.round(rng.nextDouble() * 820 + 80.0) / 100.0 * 100);
                row.put("volume", rng.nextInt(77_000_000) + 3_000_000);
                row.put("pe_ratio", Math.round(rng.nextDouble() * 60 + 10.0) / 100.0 * 100);
                row.put("return_1d", Math.round(rng.nextGaussian() * 0.02 * 1e6) / 1e6);
                rows.add(row);
            }
        }
        return rows;
    }

    /** Generate synthetic earnings data. */
    public static List<Map<String, Object>> generateEarningsData(String ticker) {
        Random rng = new Random(ticker.hashCode());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int year = 2020; year <= 2025; year++) {
            for (int q = 1; q <= 4; q++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ticker", ticker);
                row.put("quarter", "Q" + q + " " + year);
                row.put("revenue_m", Math.round(rng.nextDouble() * 100_000 + 20_000.0) / 10.0 * 10);
                row.put("eps", Math.round(rng.nextDouble() * 7.5 + 0.5) / 100.0 * 100);
                row.put("eps_estimate", Math.round(rng.nextDouble() * 7.5 + 0.5) / 100.0 * 100);
                row.put("gross_margin", Math.round(rng.nextDouble() * 0.4 + 0.3) / 1.0);
                row.put("operating_margin", Math.round(rng.nextDouble() * 0.3 + 0.1) / 1.0);
                rows.add(row);
            }
        }
        return rows;
    }

    /** Generate synthetic gene expression data. */
    public static List<Map<String, Object>> generateGeneData() {
        Random rng = new Random(42);
        String[] tissues = {"brain", "liver", "kidney", "heart", "lung"};
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            for (String tissue : tissues) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("gene_id", String.format("GENE_%04d", i));
                row.put("tissue", tissue);
                row.put("expression_level", Math.round(rng.nextDouble() * 1000 * 100) / 100.0);
                row.put("methylation_score", Math.round(rng.nextDouble() * 10000) / 10000.0);
                row.put("gc_content", Math.round((rng.nextDouble() * 0.4 + 0.3) * 10000) / 10000.0);
                row.put("chromosome", "chr" + (rng.nextInt(22) + 1));
                row.put("p_value", Math.round(rng.nextDouble() * 0.5 * 1e6) / 1e6);
                row.put("fold_change", Math.round((rng.nextGaussian() * 2.5) * 1000) / 1000.0);
                rows.add(row);
            }
        }
        return rows;
    }

    /** Generate financial factor data (for examples 10, 14). */
    public static List<Map<String, Object>> generateFactorData() {
        Random rng = new Random(42);
        String[] tickers = {"AAPL", "MSFT", "GOOGL", "AMZN", "META", "NVDA", "TSLA",
                "JPM", "GS", "BAC", "JNJ", "PFE", "UNH", "XOM", "CVX"};
        Map<String, String> sectors = Map.ofEntries(
                Map.entry("AAPL", "Tech"), Map.entry("MSFT", "Tech"), Map.entry("GOOGL", "Tech"),
                Map.entry("AMZN", "Tech"), Map.entry("META", "Tech"), Map.entry("NVDA", "Tech"),
                Map.entry("TSLA", "Tech"), Map.entry("JPM", "Finance"), Map.entry("GS", "Finance"),
                Map.entry("BAC", "Finance"), Map.entry("JNJ", "Health"), Map.entry("PFE", "Health"),
                Map.entry("UNH", "Health"), Map.entry("XOM", "Energy"), Map.entry("CVX", "Energy"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String ticker : tickers) {
            double basePrice = rng.nextDouble() * 450 + 50;
            double baseVol = rng.nextDouble() * 0.3 + 0.15;
            for (int month = 1; month <= 24; month++) {
                double drift = rng.nextGaussian() * 0.03 + 0.005;
                double price = basePrice * Math.exp(drift * month);
                double realizedVol = baseVol * (rng.nextDouble() * 0.6 + 0.7);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ticker", ticker);
                row.put("sector", sectors.get(ticker));
                row.put("month", month);
                row.put("price", Math.round(price * 100) / 100.0);
                row.put("return_1m", Math.round(rng.nextGaussian() * realizedVol / Math.sqrt(12) * 1e6) / 1e6);
                row.put("realized_vol_1m", Math.round(realizedVol * 10000) / 10000.0);
                row.put("implied_vol", Math.round(realizedVol * (rng.nextDouble() * 0.4 + 0.9) * 10000) / 10000.0);
                row.put("volume_avg_20d", rng.nextInt(95_000_000) + 5_000_000);
                row.put("pe_ratio", Math.round((rng.nextDouble() * 52 + 8) * 100) / 100.0);
                row.put("momentum_12m", Math.round(rng.nextGaussian() * 0.3 * 10000) / 10000.0);
                row.put("beta", Math.round((rng.nextDouble() * 1.5 + 0.5) * 1000) / 1000.0);
                row.put("sharpe_12m", Math.round(rng.nextGaussian() * 1.0 * 10000) / 10000.0);
                row.put("max_drawdown", Math.round((rng.nextDouble() * -0.48 - 0.02) * 10000) / 10000.0);
                row.put("market_cap_b", Math.round((rng.nextDouble() * 2970 + 30) * 10) / 10.0);
                rows.add(row);
            }
        }
        return rows;
    }

    public static void printSeparator(String title) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  " + title);
        System.out.println("=".repeat(60) + "\n");
    }
}
