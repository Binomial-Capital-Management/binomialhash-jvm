package com.binomialtechnologies.binomialhash.exporters;

import com.binomialtechnologies.binomialhash.schema.ColumnType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Generate embeddable text chunks from BinomialHash slots.
 *
 * <p>Each chunk follows the WorkbookIndexer chunk format and includes label,
 * schema, stats, preview rows, and fingerprint.</p>
 */
public final class ChunkExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChunkExporter() {}

    /**
     * Convert a single slot's metadata into an embeddable text chunk.
     *
     * @param key            BH slot key
     * @param label          dataset label
     * @param fingerprint    content fingerprint
     * @param rowCount       total rows
     * @param rows           slot row data
     * @param columns        ordered column names
     * @param colTypes       column-name → type-string map
     * @param colStats       column-name → stats map
     * @param maxColumns     max columns in the chunk description
     * @param previewRows    number of rows to include in preview
     * @param previewChars   max chars for JSON preview
     * @param maxContentChars max total content chars
     * @return chunk map with keys: content, chunk_type, cell_range, sheet_name, metadata
     */
    public static Map<String, Object> slotToChunk(
            String key,
            String label,
            String fingerprint,
            int rowCount,
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            Map<String, Map<String, Object>> colStats,
            int maxColumns,
            int previewRows,
            int previewChars,
            int maxContentChars) {

        List<String> lines = new ArrayList<>();
        lines.add(String.format("Dataset: %s (%d records, %d columns)", label, rowCount, columns.size()));
        lines.add(String.format("BH key: %s | fingerprint: %s", key, fingerprint.substring(0, Math.min(12, fingerprint.length()))));
        lines.add("Columns:");

        int colLimit = Math.min(maxColumns, columns.size());
        for (int i = 0; i < colLimit; i++) {
            String col = columns.get(i);
            String ct = colTypes.getOrDefault(col, "?");
            Map<String, Object> st = colStats.getOrDefault(col, Collections.emptyMap());
            StringBuilder desc = new StringBuilder("  ").append(col).append(" (").append(ct).append(")");

            if (ColumnType.NUMERIC.value().equals(ct) && st.containsKey("min")) {
                desc.append(String.format(" range [%s..%s] mean=%s", st.get("min"), st.get("max"), st.get("mean")));
            } else if (ColumnType.STRING.value().equals(ct) && st.containsKey("top_values")) {
                desc.append(String.format(" %s unique, top: %s",
                        st.getOrDefault("unique_count", "?"), st.get("top_values")));
            } else if (ColumnType.DATE.value().equals(ct) && st.containsKey("min_date")) {
                desc.append(String.format(" range [%s..%s]", st.get("min_date"), st.get("max_date")));
            }
            lines.add(desc.toString());
        }

        // Preview rows
        List<Map<String, Object>> previewData = rows.subList(0, Math.min(previewRows, rows.size()));
        String preview;
        try {
            preview = MAPPER.writeValueAsString(previewData);
        } catch (JsonProcessingException e) {
            preview = previewData.toString();
        }
        if (preview.length() > previewChars) {
            preview = preview.substring(0, previewChars) + "...]";
        }
        lines.add("Sample: " + preview);

        String content = String.join("\n", lines);
        if (content.length() > maxContentChars) {
            content = content.substring(0, maxContentChars);
        }

        // Metadata
        int metaColLimit = Math.min(20, columns.size());
        List<String> metaCols = columns.subList(0, metaColLimit);
        Map<String, String> metaColTypes = new LinkedHashMap<>();
        for (String col : metaCols) {
            if (colTypes.containsKey(col)) {
                metaColTypes.put(col, colTypes.get(col));
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bh_key", key);
        metadata.put("bh_fingerprint", fingerprint.substring(0, Math.min(16, fingerprint.length())));
        metadata.put("label", label);
        metadata.put("row_count", rowCount);
        metadata.put("columns", new ArrayList<>(metaCols));
        metadata.put("col_types", metaColTypes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("chunk_type", "bh_dataset");
        result.put("cell_range", null);
        result.put("sheet_name", "_bh");
        result.put("metadata", metadata);
        return result;
    }

    /** Overload with sensible defaults. */
    public static Map<String, Object> slotToChunk(
            String key,
            String label,
            String fingerprint,
            int rowCount,
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            Map<String, Map<String, Object>> colStats) {
        return slotToChunk(key, label, fingerprint, rowCount, rows, columns, colTypes, colStats,
                30, 2, 800, 6000);
    }
}
