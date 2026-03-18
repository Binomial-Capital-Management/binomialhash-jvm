package com.binomialtechnologies.binomialhash.exporters;

import com.binomialtechnologies.binomialhash.predicates.RowSorter;

import java.util.*;

/**
 * Export slot data to header+values matrix (Excel-ready format).
 */
public final class ExcelExporter {

    private ExcelExporter() {}

    /**
     * Return a map with {@code headers} and {@code values} ready for Excel output.
     *
     * @param rows           slot row data
     * @param columns        ordered column names
     * @param colTypes       column-name → type-string map
     * @param key            BH slot key
     * @param label          dataset label
     * @param rowCount       total available row count
     * @param selectColumns  subset of columns ({@code null} = all)
     * @param sortBy         column to sort by ({@code null} = no sort)
     * @param sortDesc       sort direction
     * @param maxRows        hard cap on exported rows
     * @return map with keys: key, label, headers, values, total_exported, total_available
     */
    public static Map<String, Object> exportExcelBatch(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            String key,
            String label,
            int rowCount,
            List<String> selectColumns,
            String sortBy,
            boolean sortDesc,
            int maxRows) {

        List<Map<String, Object>> work = new ArrayList<>(rows);
        if (sortBy != null && colTypes.containsKey(sortBy)) {
            work = RowSorter.sortRows(work, sortBy, colTypes.get(sortBy), sortDesc);
        }
        if (work.size() > maxRows) {
            work = work.subList(0, maxRows);
        }

        List<String> headers = selectColumns != null ? new ArrayList<>(selectColumns) : new ArrayList<>(columns);

        List<List<Object>> values = new ArrayList<>();
        for (Map<String, Object> row : work) {
            List<Object> vals = new ArrayList<>();
            for (String col : headers) {
                vals.add(row.get(col));
            }
            values.add(vals);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("label", label);
        result.put("headers", headers);
        result.put("values", values);
        result.put("total_exported", values.size());
        result.put("total_available", rowCount);
        return result;
    }

    /** Overload with sensible defaults: 200 max rows, descending. */
    public static Map<String, Object> exportExcelBatch(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            String key,
            String label,
            int rowCount) {
        return exportExcelBatch(rows, columns, colTypes, key, label, rowCount, null, null, true, 200);
    }
}
