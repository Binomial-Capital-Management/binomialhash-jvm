package com.binomialtechnologies.binomialhash.exporters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Downloadable artifact wrapper for chat frontends.
 *
 * <p>Bundles exported data with a filename and MIME type so frontend
 * renderers can create download links or inline previews.</p>
 */
public final class ArtifactExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String[]> FORMAT_META = new LinkedHashMap<>();

    static {
        FORMAT_META.put("csv",      new String[]{"csv",   "text/csv"});
        FORMAT_META.put("markdown", new String[]{"md",    "text/markdown"});
        FORMAT_META.put("json",     new String[]{"json",  "application/json"});
        FORMAT_META.put("jsonl",    new String[]{"jsonl", "application/x-ndjson"});
    }

    private ArtifactExporter() {}

    /**
     * Build a downloadable artifact from slot data.
     *
     * @param rows           slot row data
     * @param columns        ordered column names
     * @param colTypes       column-name → type-string map
     * @param format         one of "csv", "markdown", "json", "jsonl"
     * @param label          used to generate the filename
     * @param selectColumns  subset of columns ({@code null} = all)
     * @param sortBy         column to sort by ({@code null} = no sort)
     * @param sortDesc       sort direction
     * @param maxRows        hard cap on exported rows
     * @param totalRows      total available rows for markdown footer ({@code null} to omit)
     * @return map with keys: type, filename, mime_type, content, row_count, format
     */
    public static Map<String, Object> buildArtifact(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            String format,
            String label,
            List<String> selectColumns,
            String sortBy,
            boolean sortDesc,
            int maxRows,
            Integer totalRows) {

        String[] meta = FORMAT_META.get(format);
        if (meta == null) {
            throw new IllegalArgumentException(
                    "Unknown format '" + format + "'. Choose from: " + FORMAT_META.keySet());
        }

        String content;
        int rowCount;

        switch (format) {
            case "csv":
                content = CsvExporter.exportCsv(
                        rows, columns, colTypes, selectColumns, sortBy, sortDesc, maxRows, true);
                rowCount = (int) content.chars().filter(c -> c == '\n').count() - 1;
                break;

            case "markdown":
                content = MarkdownExporter.exportMarkdown(
                        rows, columns, colTypes, selectColumns, sortBy, sortDesc,
                        maxRows, 40, totalRows, label);
                rowCount = Math.min(rows.size(), maxRows);
                break;

            case "json": {
                List<Map<String, Object>> exported = RowsExporter.exportRows(
                        rows, columns, colTypes, selectColumns, sortBy, sortDesc, 0, maxRows);
                try {
                    content = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(exported);
                } catch (JsonProcessingException e) {
                    content = exported.toString();
                }
                rowCount = exported.size();
                break;
            }

            case "jsonl": {
                List<Map<String, Object>> exported = RowsExporter.exportRows(
                        rows, columns, colTypes, selectColumns, sortBy, sortDesc, 0, maxRows);
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> r : exported) {
                    try {
                        sb.append(MAPPER.writeValueAsString(r));
                    } catch (JsonProcessingException e) {
                        sb.append(r.toString());
                    }
                    sb.append('\n');
                }
                content = sb.toString().stripTrailing();
                rowCount = exported.size();
                break;
            }

            default:
                throw new IllegalArgumentException("Unhandled format: " + format);
        }

        StringBuilder safeLabel = new StringBuilder();
        for (char c : label.toCharArray()) {
            safeLabel.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        String filename = safeLabel + "." + meta[0];

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "artifact");
        result.put("filename", filename);
        result.put("mime_type", meta[1]);
        result.put("content", content);
        result.put("row_count", rowCount);
        result.put("format", format);
        return result;
    }

    /** Overload with sensible defaults: csv format, 500 max rows. */
    public static Map<String, Object> buildArtifact(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            String label) {
        return buildArtifact(rows, columns, colTypes, "csv", label, null, null, true, 500, null);
    }
}
