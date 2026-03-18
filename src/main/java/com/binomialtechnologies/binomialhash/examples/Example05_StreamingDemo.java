package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Java equivalent of the FastAPI streaming demo. Demonstrates BH compression:
 * large JSON payload → ingest → compact summary. Incremental retrieve by page.
 */
public class Example05_StreamingDemo {

    public static void main(String[] args) {
        BinomialHash bh = new BinomialHash();

        printSeparator("Simulate streaming: generate large JSON");
        String largePayload = generateLargePayload();
        System.out.println("Raw size: " + largePayload.length() + " chars");

        printSeparator("Ingest through BH");
        String summary = bh.ingest(largePayload, "streamed_data");
        System.out.println("Summary size: " + summary.length() + " chars");
        System.out.println("Compression: " + String.format("%.1fx", (double) largePayload.length() / Math.max(summary.length(), 1)));

        List<Map<String, Object>> keys = bh.keys();
        String key = keys.isEmpty() ? null : (String) keys.get(0).get("key");
        if (key == null) {
            System.out.println("No slot created.");
            return;
        }

        printSeparator("Incremental tool calls: retrieve page 1, 2, 3");
        for (int page = 1; page <= 3; page++) {
            int offset = (page - 1) * 10;
            Map<String, Object> result = bh.retrieve(key, offset, 10, null, true, null);
            System.out.println("Page " + page + ": offset=" + offset + ", returned=" + result.get("returned"));
            System.out.println("Context stats: " + toJson(bh.contextStats()));
        }
    }

    private static String generateLargePayload() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("symbol", "SYM" + (i % 50));
            row.put("price", 100.0 + i * 0.5);
            row.put("volume", 1_000_000 + i * 1000);
            row.put("timestamp", "2025-03-" + String.format("%02d", (i % 28) + 1));
            rows.add(row);
        }
        return toJson(rows);
    }
}
