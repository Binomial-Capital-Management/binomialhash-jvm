package com.binomialtechnologies.binomialhash.manifold;

import java.util.*;

/**
 * Axis discovery helpers for BinomialHash manifold construction.
 *
 * <p>Separates columns into parameter-space axes vs numeric field values.</p>
 */
public final class AxesIdentifier {

    private AxesIdentifier() {}

    public static class AxesResult {
        public final List<ManifoldAxis> axes;
        public final List<String> fields;
        public AxesResult(List<ManifoldAxis> axes, List<String> fields) {
            this.axes = axes;
            this.fields = fields;
        }
    }

    public static AxesResult identifyAxes(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            Map<String, Map<String, Object>> colStats) {

        int n = rows.size();
        if (n == 0) return new AxesResult(new ArrayList<>(), new ArrayList<>());

        Map<String, List<Object>> uniques = new LinkedHashMap<>();
        Map<String, Integer> uniqueCounts = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> valueCounts = new LinkedHashMap<>();

        for (String col : columns) {
            Set<String> seen = new LinkedHashSet<>();
            List<Object> vals = new ArrayList<>();
            Map<String, Integer> freq = new LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                Object v = r.get(col);
                if (v == null || "".equals(v)) continue;
                String sv = String.valueOf(v);
                freq.merge(sv, 1, Integer::sum);
                if (seen.add(sv)) vals.add(v);
            }
            uniques.put(col, vals);
            uniqueCounts.put(col, vals.size());
            valueCounts.put(col, freq);
        }

        List<String> seedAxes = new ArrayList<>();
        for (String col : columns) {
            String ct = colTypes.getOrDefault(col, "string");
            int u = uniqueCounts.getOrDefault(col, 0);
            if (u < 2) continue;
            if ("date".equals(ct) || "string".equals(ct) || "bool".equals(ct)) {
                seedAxes.add(col);
            }
        }

        List<String> selectedAxes = new ArrayList<>();
        for (String col : seedAxes) {
            int u = uniqueCounts.get(col);
            int maxCard = Math.max(2, (int) (Math.sqrt(n) * 6));
            if (u >= 2 && u <= maxCard) {
                selectedAxes.add(col);
            }
        }

        List<String> numericCandidates = new ArrayList<>();
        for (String c : columns) {
            if ("numeric".equals(colTypes.get(c)) && uniqueCounts.getOrDefault(c, 0) >= 2) {
                numericCandidates.add(c);
            }
        }

        String bestNumAxis = null;
        double bestScore = -1.0;
        long baseProd = 1;
        for (String c : selectedAxes) baseProd *= Math.max(uniqueCounts.getOrDefault(c, 1), 1);

        for (String c : numericCandidates) {
            int u = uniqueCounts.get(c);
            Map<String, Integer> freq = valueCounts.get(c);
            double repeats = (double) n / Math.max(u, 1);
            double ent = entropyNorm(freq);

            List<String> colsForCov = new ArrayList<>(selectedAxes);
            colsForCov.add(c);
            int distinctTuples = distinctTupleCount(rows, colsForCov);
            long expected = Math.max(baseProd * u, 1);
            double coverage = (double) distinctTuples / expected;

            double score = coverage * Math.log1p(repeats) * (0.5 + 0.5 * ent) * Math.log1p(u);
            if (score > bestScore) {
                bestScore = score;
                bestNumAxis = c;
            }
        }

        if (bestNumAxis != null) {
            long testProd = baseProd * Math.max(uniqueCounts.getOrDefault(bestNumAxis, 1), 1);
            double testOccupancy = (double) n / Math.max(testProd, 1);
            if (testOccupancy >= 0.3) {
                selectedAxes.add(bestNumAxis);
            }
        }

        List<ManifoldAxis> axes = new ArrayList<>();
        for (String col : selectedAxes) {
            List<Object> vals = new ArrayList<>(uniques.get(col));
            String ct = colTypes.getOrDefault(col, "string");
            String axisType;
            boolean ordered;
            if ("numeric".equals(ct)) {
                axisType = "numeric_ordered";
                ordered = true;
                vals.sort((a, b) -> {
                    try { return Double.compare(Double.parseDouble(a.toString()), Double.parseDouble(b.toString())); }
                    catch (NumberFormatException e) { return a.toString().compareTo(b.toString()); }
                });
            } else if ("date".equals(ct)) {
                axisType = "temporal";
                ordered = true;
                vals.sort(Comparator.comparing(Object::toString));
            } else {
                axisType = "categorical";
                ordered = false;
            }
            axes.add(new ManifoldAxis(col, vals, ordered, axisType, vals.size()));
        }

        Set<String> axisSet = new HashSet<>();
        for (ManifoldAxis a : axes) axisSet.add(a.getColumn());
        List<String> fields = new ArrayList<>();
        for (String c : columns) {
            if ("numeric".equals(colTypes.get(c)) && !axisSet.contains(c)) fields.add(c);
        }

        if (fields.isEmpty()) {
            List<ManifoldAxis> numericAxes = new ArrayList<>();
            for (ManifoldAxis a : axes) {
                if ("numeric_ordered".equals(a.getAxisType())) numericAxes.add(a);
            }
            numericAxes.sort((a, b) -> Integer.compare(b.getSize(), a.getSize()));
            while (!numericAxes.isEmpty() && fields.isEmpty()) {
                ManifoldAxis demote = numericAxes.remove(0);
                axes.removeIf(a -> a.getColumn().equals(demote.getColumn()));
                fields.add(demote.getColumn());
            }
        }

        axes.sort(Comparator.comparingInt(ManifoldAxis::getSize));
        List<ManifoldAxis> cappedAxes = axes.size() > 6 ? axes.subList(0, 6) : axes;
        List<String> cappedFields = fields.size() > 20 ? fields.subList(0, 20) : fields;
        return new AxesResult(new ArrayList<>(cappedAxes), new ArrayList<>(cappedFields));
    }

    private static double entropyNorm(Map<String, Integer> freq) {
        int total = 0;
        for (int v : freq.values()) total += v;
        if (total <= 0 || freq.size() <= 1) return 0.0;
        double h = 0;
        for (int v : freq.values()) {
            double p = (double) v / total;
            h -= p * (Math.log(Math.max(p, 1e-12)) / Math.log(2));
        }
        double hMax = Math.log(freq.size()) / Math.log(2);
        return hMax > 0 ? h / hMax : 0.0;
    }

    private static int distinctTupleCount(List<Map<String, Object>> rows, List<String> cols) {
        if (cols.isEmpty()) return 1;
        Set<List<String>> tuples = new HashSet<>();
        for (Map<String, Object> r : rows) {
            List<String> t = new ArrayList<>();
            for (String c : cols) t.add(String.valueOf(r.getOrDefault(c, "")));
            tuples.add(t);
        }
        return tuples.size();
    }
}
