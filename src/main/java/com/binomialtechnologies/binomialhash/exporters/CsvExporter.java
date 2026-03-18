package com.binomialtechnologies.binomialhash.exporters;

import com.binomialtechnologies.binomialhash.predicates.RowSorter;

import java.util.*;

/**
 * Export slot data to CSV string.
 *
 * <p>Pure-JDK implementation. Returns a plain string so callers can write it
 * to a file, stream it as a download, or embed it in a chat artifact.</p>
 */
public final class CsvExporter {

    private CsvExporter() {}

    /**
     * Render rows as a CSV string.
     *
     * @param rows           slot row data
     * @param columns        ordered column names
     * @param colTypes       column-name → type-string map
     * @param selectColumns  subset of columns to include ({@code null} = all)
     * @param sortBy         column to sort by before export ({@code null} = no sort)
     * @param sortDesc       sort direction (true = descending)
     * @param maxRows        hard cap on exported rows
     * @param includeHeader  whether to emit a header row
     * @return CSV-formatted string
     */
    public static String exportCsv(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            List<String> selectColumns,
            String sortBy,
            boolean sortDesc,
            int maxRows,
            boolean includeHeader) {

        List<Map<String, Object>> work = new ArrayList<>(rows);
        if (sortBy != null && colTypes.containsKey(sortBy)) {
            work = RowSorter.sortRows(work, sortBy, colTypes.get(sortBy), sortDesc);
        }
        if (work.size() > maxRows) {
            work = work.subList(0, maxRows);
        }

        List<String> headers = selectColumns != null ? new ArrayList<>(selectColumns) : new ArrayList<>(columns);

        StringBuilder buf = new StringBuilder();
        if (includeHeader) {
            appendCsvRow(buf, headers, headers);
        }
        for (Map<String, Object> row : work) {
            appendCsvDataRow(buf, row, headers);
        }
        return buf.toString();
    }

    /** Overload with sensible defaults: header on, 500 max rows, descending. */
    public static String exportCsv(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes) {
        return exportCsv(rows, columns, colTypes, null, null, true, 500, true);
    }

    private static void appendCsvRow(StringBuilder buf, List<String> values, List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) buf.append(',');
            buf.append(escapeCsv(headers.get(i)));
        }
        buf.append('\n');
    }

    private static void appendCsvDataRow(StringBuilder buf, Map<String, Object> row, List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) buf.append(',');
            Object val = row.getOrDefault(headers.get(i), "");
            buf.append(escapeCsv(val == null ? "" : val.toString()));
        }
        buf.append('\n');
    }

    private static String escapeCsv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
