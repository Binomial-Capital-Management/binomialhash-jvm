package com.binomialtechnologies.binomialhash.exporters;

import com.binomialtechnologies.binomialhash.predicates.RowSorter;
import com.binomialtechnologies.binomialhash.schema.ColumnType;

import java.util.*;

/**
 * Export slot data to a GitHub Flavored Markdown table.
 *
 * <p>Designed for inline rendering in chat UIs (React, Slack, etc.).
 * Numbers are right-aligned, strings left-aligned. Long cell values
 * are truncated to keep the table readable in constrained viewports.</p>
 */
public final class MarkdownExporter {

    private MarkdownExporter() {}

    /**
     * Render rows as a Markdown table string.
     *
     * @param rows           slot row data
     * @param columns        ordered column names
     * @param colTypes       column-name → type-string map
     * @param selectColumns  subset of columns ({@code null} = all)
     * @param sortBy         column to sort by ({@code null} = no sort)
     * @param sortDesc       sort direction
     * @param maxRows        row cap for the table
     * @param maxCellWidth   truncate cell values wider than this
     * @param totalRows      total available rows for the footer note ({@code null} = use rows.size())
     * @param label          dataset label for the caption ({@code null} = omit)
     * @return Markdown-formatted table string
     */
    public static String exportMarkdown(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            List<String> selectColumns,
            String sortBy,
            boolean sortDesc,
            int maxRows,
            int maxCellWidth,
            Integer totalRows,
            String label) {

        List<Map<String, Object>> work = new ArrayList<>(rows);
        if (sortBy != null && colTypes.containsKey(sortBy)) {
            work = RowSorter.sortRows(work, sortBy, colTypes.get(sortBy), sortDesc);
        }

        List<Map<String, Object>> displayRows = work.size() > maxRows
                ? work.subList(0, maxRows) : work;
        List<String> headers = selectColumns != null ? new ArrayList<>(selectColumns) : new ArrayList<>(columns);

        List<String> lines = new ArrayList<>();

        // Header row
        lines.add("| " + String.join(" | ", headers) + " |");

        // Alignment row
        StringJoiner alignJoiner = new StringJoiner(" | ", "| ", " |");
        for (String h : headers) {
            alignJoiner.add(alignMarker(h, colTypes));
        }
        lines.add(alignJoiner.toString());

        // Data rows
        for (Map<String, Object> row : displayRows) {
            StringJoiner cellJoiner = new StringJoiner(" | ", "| ", " |");
            for (String h : headers) {
                cellJoiner.add(fmtCell(row.get(h), maxCellWidth));
            }
            lines.add(cellJoiner.toString());
        }

        // Footer
        int actualTotal = totalRows != null ? totalRows : work.size();
        if (displayRows.size() < actualTotal) {
            lines.add("");
            String footer = "*Showing " + displayRows.size() + " of " + actualTotal + " rows";
            if (label != null) {
                footer += " from **" + label + "**";
            }
            footer += ".*";
            lines.add(footer);
        }

        return String.join("\n", lines);
    }

    /** Overload with sensible defaults: 50 max rows, 40 cell width, descending. */
    public static String exportMarkdown(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes) {
        return exportMarkdown(rows, columns, colTypes, null, null, true, 50, 40, null, null);
    }

    private static String fmtCell(Object value, int maxWidth) {
        if (value == null) return "";
        String s = value.toString().replace("|", "\\|");
        if (s.length() > maxWidth) {
            return s.substring(0, maxWidth - 1) + "\u2026";
        }
        return s;
    }

    private static String alignMarker(String col, Map<String, String> colTypes) {
        if (ColumnType.NUMERIC.value().equals(colTypes.get(col))) {
            return "---:";
        }
        return ":---";
    }
}
