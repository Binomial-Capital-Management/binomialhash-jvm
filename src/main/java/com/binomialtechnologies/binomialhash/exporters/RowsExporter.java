package com.binomialtechnologies.binomialhash.exporters;

import com.binomialtechnologies.binomialhash.predicates.RowSorter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Export slot data as clean row dicts.
 *
 * <p>Standalone row export with column selection, sorting, and pagination.
 * Returns plain Maps — no BH metadata overhead — suitable for frontend
 * table components, DataFrame construction, or further piping.</p>
 */
public final class RowsExporter {

    private RowsExporter() {}

    /**
     * Return a clean list of row maps from slot data.
     *
     * @param rows           slot row data
     * @param columns        ordered column names
     * @param colTypes       column-name → type-string map
     * @param selectColumns  subset of columns ({@code null} = all)
     * @param sortBy         column to sort by ({@code null} = no sort)
     * @param sortDesc       sort direction
     * @param offset         skip this many rows (after sorting)
     * @param limit          maximum rows to return
     * @return list of row maps
     */
    public static List<Map<String, Object>> exportRows(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            List<String> selectColumns,
            String sortBy,
            boolean sortDesc,
            int offset,
            int limit) {

        List<Map<String, Object>> work = new ArrayList<>(rows);
        if (sortBy != null && colTypes.containsKey(sortBy)) {
            work = RowSorter.sortRows(work, sortBy, colTypes.get(sortBy), sortDesc);
        }

        int fromIndex = Math.min(offset, work.size());
        int toIndex = Math.min(offset + limit, work.size());
        List<Map<String, Object>> sliced = work.subList(fromIndex, toIndex);

        if (selectColumns != null) {
            Set<String> colSet = new HashSet<>(selectColumns);
            return sliced.stream()
                    .map(r -> {
                        Map<String, Object> filtered = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> e : r.entrySet()) {
                            if (colSet.contains(e.getKey())) {
                                filtered.put(e.getKey(), e.getValue());
                            }
                        }
                        return filtered;
                    })
                    .collect(Collectors.toList());
        }
        return sliced.stream()
                .map(LinkedHashMap::new)
                .collect(Collectors.toList());
    }

    /** Overload with sensible defaults: 0 offset, 200 limit, descending. */
    public static List<Map<String, Object>> exportRows(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes) {
        return exportRows(rows, columns, colTypes, null, null, true, 0, 200);
    }
}
