package com.binomialtechnologies.binomialhash.examples;

import com.binomialtechnologies.binomialhash.BinomialHash;
import com.binomialtechnologies.binomialhash.BinomialHashSlot;
import com.binomialtechnologies.binomialhash.exporters.ArtifactExporter;
import com.binomialtechnologies.binomialhash.exporters.CsvExporter;
import com.binomialtechnologies.binomialhash.exporters.MarkdownExporter;

import java.util.List;
import java.util.Map;

import static com.binomialtechnologies.binomialhash.examples.ExampleUtils.*;

/**
 * Export formats demonstration: CSV, Markdown, and artifact.
 */
public final class Example12_ExportArtifacts {

    public static void main(String[] args) {
        printSeparator("Example 12: Export Artifacts");

        // 1. Create BH, ingest market data
        BinomialHash bh = new BinomialHash();
        List<Map<String, Object>> marketData = generateMarketData();
        bh.ingest(toJson(marketData), "market_data");
        String key = (String) bh.keys().get(0).get("key");

        // 2. Get the slot
        BinomialHashSlot slot = bh.slot(key);
        if (slot == null) {
            System.out.println("Slot not found (data may be below ingest threshold).");
            return;
        }

        // 3. Export as CSV
        printSeparator("CSV Export");
        String csv = CsvExporter.exportCsv(
                slot.rows(), slot.columns(), slot.colTypes(),
                null, null, true, 100, true);
        String csvPreview = csv.length() > 500 ? csv.substring(0, 500) + "..." : csv;
        System.out.println("CSV (first 500 chars):\n" + csvPreview);
        System.out.println("\nCSV total length: " + csv.length() + " chars");

        // 4. Export as Markdown
        printSeparator("Markdown Export");
        String md = MarkdownExporter.exportMarkdown(
                slot.rows(), slot.columns(), slot.colTypes(),
                null, null, true, 20, 40, slot.rowCount(), slot.label());
        String mdPreview = md.length() > 500 ? md.substring(0, 500) + "..." : md;
        System.out.println("Markdown (first 500 chars):\n" + mdPreview);
        System.out.println("\nMarkdown total length: " + md.length() + " chars");

        // 5. Build artifact
        printSeparator("Artifact Export");
        Map<String, Object> artifact = ArtifactExporter.buildArtifact(
                slot.rows(), slot.columns(), slot.colTypes(),
                "csv", slot.label(), null, null, true, 100, slot.rowCount());
        System.out.println("Artifact structure: type=" + artifact.get("type")
                + ", filename=" + artifact.get("filename")
                + ", mime_type=" + artifact.get("mime_type")
                + ", row_count=" + artifact.get("row_count")
                + ", format=" + artifact.get("format"));
        String content = (String) artifact.get("content");
        if (content != null) {
            System.out.println("Content length: " + content.length() + " chars");
        }

        // 6. Show sizes
        printSeparator("Format Sizes");
        System.out.println("CSV:       " + csv.length() + " chars");
        System.out.println("Markdown:  " + md.length() + " chars");
        System.out.println("Artifact:  " + (content != null ? content.length() : 0) + " chars (content)");
    }
}
