package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * RAG pipeline demonstration: document chunks, query, aggregate, group by topic.
 */
public final class Example11_RagRetrieval {

    public static void main(String[] args) {
        printSeparator("Example 11: RAG Retrieval Pipeline");

        // 1. Generate "document chunks" as list of maps (enough data to exceed ingest threshold)
        List<Map<String, Object>> chunks = new ArrayList<>();
        String[] topics = {"finance", "healthcare", "technology", "energy"};
        java.util.Random rng = new java.util.Random(42);
        for (int docId = 1; docId <= 10; docId++) {
            for (int chunkId = 1; chunkId <= 8; chunkId++) {
                Map<String, Object> chunk = new LinkedHashMap<>();
                chunk.put("doc_id", "doc_" + docId);
                chunk.put("chunk_id", chunkId);
                chunk.put("text", "Chunk " + chunkId + " of document " + docId + " about " + topics[docId % topics.length]
                        + ". This is extended content to ensure the payload exceeds the ingest threshold for caching.");
                chunk.put("score", Math.round((0.5 + rng.nextDouble() * 0.5) * 1000.0) / 1000.0);
                chunk.put("topic", topics[docId % topics.length]);
                chunks.add(chunk);
            }
        }

        // 2. Create BH, ingest chunks as JSON
        BinomialHash bh = new BinomialHash();
        String rawJson = toJson(chunks);
        bh.ingest(rawJson, "rag_chunks");
        String key = (String) bh.keys().get(0).get("key");

        // 3. Query: find chunks where score > 0.8
        printSeparator("Query: score > 0.8");
        Map<String, Object> qResult = bh.query(key, "{\"score\": {\"$gt\": 0.8}}", "score", true, 20, null);
        System.out.println("Matched: " + qResult.get("matched") + ", returned: " + qResult.get("returned"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> qRows = (List<Map<String, Object>>) qResult.get("rows");
        if (qRows != null && !qRows.isEmpty()) {
            System.out.println("Retrieved chunks:");
            for (Map<String, Object> r : qRows) {
                System.out.println("  " + r.get("doc_id") + " chunk " + r.get("chunk_id") + " | topic=" + r.get("topic") + " | score=" + r.get("score"));
            }
        }

        // 4. Aggregate: count and mean score
        printSeparator("Aggregate: count and mean score");
        Map<String, Object> countAgg = bh.aggregate(key, "chunk_id", "count");
        Map<String, Object> meanAgg = bh.aggregate(key, "score", "mean");
        System.out.println("Total chunks: " + countAgg.get("result"));
        System.out.println("Mean score: " + meanAgg.get("result"));

        // 5. Group by topic: count per topic
        printSeparator("Group By Topic: count per topic");
        String aggJson = "[{\"column\": \"chunk_id\", \"func\": \"count\", \"alias\": \"chunk_count\"}]";
        Map<String, Object> gbResult = bh.groupBy(key, List.of("topic"), aggJson, "chunk_count", true, 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gbRows = (List<Map<String, Object>>) gbResult.get("rows");
        if (gbRows != null) {
            System.out.println("Chunks per topic:");
            for (Map<String, Object> r : gbRows) {
                System.out.println("  " + r.get("topic") + ": " + r.get("chunk_count") + " chunks");
            }
        }

        printSeparator("Done");
    }
}
