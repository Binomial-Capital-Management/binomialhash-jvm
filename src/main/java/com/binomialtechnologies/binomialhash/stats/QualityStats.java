package com.binomialtechnologies.binomialhash.stats;

import java.util.*;

import static com.binomialtechnologies.binomialhash.stats.StatsHelpers.*;

/**
 * Data quality profiling and diagnostics: distribution, outliers, Benford, VIF.
 */
public final class QualityStats {

    private QualityStats() {}

    private static double quantile(double[] sorted, double q) {
        int n = sorted.length;
        double pos = q * (n - 1);
        int lo = (int) pos;
        int hi = Math.min(lo + 1, n - 1);
        double frac = pos - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    public static Map<String, Object> distributionDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String field, Integer bins, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        if (!colTypes.containsKey(field)) return Map.of("error", "Field '" + field + "' not found.");
        List<Double> vals = new ArrayList<>();
        for (var r : rows) { Double v = toFloatPermissive(r.get(field)); if (v != null) vals.add(v); }
        int n = vals.size();
        if (n < policy.distributionMinValues) return Map.of("error", "Not enough values (" + n + ").");
        if (bins == null) bins = policy.distributionDefaultBins;

        double[] sv = vals.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double mean = Arrays.stream(sv).sum() / n;
        double median = n % 2 != 0 ? sv[n / 2] : (sv[n / 2 - 1] + sv[n / 2]) / 2.0;
        double var = Arrays.stream(sv).map(x -> (x - mean) * (x - mean)).sum() / n;
        double std = var > 0 ? Math.sqrt(var) : 0;
        double skewness = 0, kurtosis = 0;
        if (std > 1e-12) {
            for (double x : sv) { double z = (x - mean) / std; skewness += z * z * z; kurtosis += z * z * z * z; }
            skewness /= n; kurtosis = kurtosis / n - 3.0;
        }

        Map<String, Object> quantiles = new LinkedHashMap<>();
        quantiles.put("p5", r6(quantile(sv, 0.05)));
        quantiles.put("p25", r6(quantile(sv, 0.25)));
        quantiles.put("p50", r6(quantile(sv, 0.50)));
        quantiles.put("p75", r6(quantile(sv, 0.75)));
        quantiles.put("p95", r6(quantile(sv, 0.95)));

        double loVal = sv[0], hiVal = sv[n - 1];
        double binWidth = hiVal > loVal ? (hiVal - loVal) / bins : 1.0;
        int[] histCounts = new int[bins];
        List<Double> histEdges = new ArrayList<>();
        for (int i = 0; i <= bins; i++) histEdges.add(r6(loVal + i * binWidth));
        for (double v : sv) {
            int idx = binWidth > 0 ? Math.min((int) ((v - loVal) / binWidth), bins - 1) : 0;
            histCounts[idx]++;
        }

        double jb = n / 6.0 * (skewness * skewness + kurtosis * kurtosis / 4.0);
        double normPval = r6(Math.exp(-jb / 2.0));

        String shape;
        if (Math.abs(skewness) < 0.5 && Math.abs(kurtosis) < 1.0) shape = "normal";
        else if (skewness > 1.0) shape = "right_skewed";
        else if (skewness < -1.0) shape = "left_skewed";
        else if (kurtosis > 3.0) shape = "heavy_tailed";
        else if (Math.abs(skewness) < 0.3 && kurtosis < -1.0) shape = "uniform";
        else {
            int peaks = 0;
            for (int i = 1; i < bins - 1; i++)
                if (histCounts[i] > histCounts[i - 1] && histCounts[i] > histCounts[i + 1]) peaks++;
            shape = peaks >= 2 ? "bimodal" : "moderate_skew";
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("field", field); res.put("count", n);
        res.put("mean", r6(mean)); res.put("median", r6(median)); res.put("std", r6(std));
        res.put("skewness", r4(skewness)); res.put("kurtosis", r4(kurtosis));
        res.put("min", r6(sv[0])); res.put("max", r6(sv[n - 1]));
        res.put("quantiles", quantiles);
        res.put("histogram", Map.of("edges", histEdges, "counts", histCounts));
        res.put("normality_pvalue", normPval); res.put("shape_label", shape);
        return res;
    }

    public static Map<String, Object> outliersDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            List<String> fields, String method, Double threshold, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        if (method == null) method = "both";
        List<String> numericCols = new ArrayList<>();
        for (var e : colTypes.entrySet()) if ("numeric".equals(e.getValue())) numericCols.add(e.getKey());
        if (fields == null || fields.isEmpty()) fields = numericCols.subList(0, Math.min(20, numericCols.size()));
        List<String> finalFields = new ArrayList<>();
        for (String f : fields) if (numericCols.contains(f)) finalFields.add(f);
        if (finalFields.isEmpty()) return Map.of("error", "No valid numeric fields.");

        double zThresh = threshold != null ? threshold : policy.outlierDefaultZscore;
        double iqrMult = policy.outlierDefaultIqrMultiplier;
        List<Map<String, Object>> fieldSummaries = new ArrayList<>();
        Map<Integer, Double> rowScores = new HashMap<>();

        for (String fld : finalFields) {
            List<double[]> valPairs = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Double v = toFloatPermissive(rows.get(i).get(fld));
                if (v != null) valPairs.add(new double[]{i, v});
            }
            if (valPairs.size() < 5) continue;
            double[] numbers = valPairs.stream().mapToDouble(p -> p[1]).toArray();
            double mean = Arrays.stream(numbers).sum() / numbers.length;
            double std = Math.sqrt(Arrays.stream(numbers).map(x -> (x - mean) * (x - mean)).sum() / numbers.length);
            double[] sorted = numbers.clone(); Arrays.sort(sorted);
            double q1 = quantile(sorted, 0.25), q3 = quantile(sorted, 0.75), iqr = q3 - q1;
            double loFence = q1 - iqrMult * iqr, hiFence = q3 + iqrMult * iqr;
            int nOut = 0, nHigh = 0, nLow = 0;
            for (double[] vp : valPairs) {
                int idx = (int) vp[0]; double v = vp[1];
                boolean out = false;
                if (("zscore".equals(method) || "both".equals(method)) && std > 1e-12 && Math.abs(v - mean) / std > zThresh) out = true;
                if (("iqr".equals(method) || "both".equals(method)) && (v < loFence || v > hiFence)) out = true;
                if (out) {
                    nOut++;
                    double sev = std > 1e-12 ? Math.abs(v - mean) / std : 0;
                    rowScores.merge(idx, sev, Double::sum);
                    if (v > mean) nHigh++; else nLow++;
                }
            }
            Map<String, Object> fs = new LinkedHashMap<>();
            fs.put("field", fld); fs.put("outlier_count", nOut); fs.put("high", nHigh); fs.put("low", nLow);
            fs.put("total_values", valPairs.size());
            fieldSummaries.add(fs);
        }

        List<Map.Entry<Integer, Double>> topFlagged = rowScores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(policy.outlierMaxFlagged).toList();
        int totalOutFields = fieldSummaries.stream().mapToInt(fs -> (int) fs.get("outlier_count")).sum();
        int totalVals = fieldSummaries.stream().mapToInt(fs -> (int) fs.get("total_values")).sum();
        double qs = r4(1.0 - (double) totalOutFields / Math.max(totalVals, 1));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("method", method); res.put("z_threshold", zThresh); res.put("iqr_multiplier", iqrMult);
        res.put("field_summaries", fieldSummaries);
        List<Map<String, Object>> flagged = new ArrayList<>();
        for (var e : topFlagged) flagged.add(Map.of("row_index", e.getKey(), "severity", r4(e.getValue())));
        res.put("top_flagged_rows", flagged); res.put("data_quality_score", qs);
        return res;
    }

    public static Map<String, Object> benfordDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String field, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        if (!colTypes.containsKey(field)) return Map.of("error", "Field '" + field + "' not found.");
        List<Double> vals = new ArrayList<>();
        for (var r : rows) {
            Double v = toFloatPermissive(r.get(field));
            if (v != null && v != 0) vals.add(Math.abs(v));
        }
        if (vals.size() < policy.benfordMinValues) return Map.of("error", "Need " + policy.benfordMinValues + " non-zero values.");

        int[] observed = new int[10];
        for (double v : vals) {
            String s = String.valueOf(v).replaceFirst("^0*\\.?0*", "");
            if (!s.isEmpty()) {
                int d = s.charAt(0) - '0';
                if (d >= 1 && d <= 9) observed[d]++;
            }
        }
        int total = 0; for (int d = 1; d <= 9; d++) total += observed[d];
        if (total == 0) return Map.of("error", "No valid leading digits.");

        double[] expected = new double[10];
        for (int d = 1; d <= 9; d++) expected[d] = Math.log10(1 + 1.0 / d);
        double chi2 = 0;
        List<Map<String, Object>> digits = new ArrayList<>();
        for (int d = 1; d <= 9; d++) {
            double obsPct = (double) observed[d] / total;
            double expCount = expected[d] * total;
            if (expCount > 0) chi2 += (observed[d] - expCount) * (observed[d] - expCount) / expCount;
            digits.add(Map.of("digit", d, "observed_pct", r4(obsPct), "expected_pct", r4(expected[d]),
                    "deviation", r4(obsPct - expected[d])));
        }
        double pValue = chi2Pvalue(chi2, 8);
        String label = pValue > 0.10 ? "conforms" : pValue > 0.01 ? "suspicious" : "fails";
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("field", field); res.put("sample_size", total);
        res.put("chi_squared", r4(chi2)); res.put("p_value", r6(pValue));
        res.put("conformity_label", label); res.put("digits", digits);
        return res;
    }

    public static Map<String, Object> vifDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            List<String> fields, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        List<String> numericCols = new ArrayList<>();
        for (var e : colTypes.entrySet()) if ("numeric".equals(e.getValue())) numericCols.add(e.getKey());
        if (fields == null || fields.isEmpty()) fields = numericCols.subList(0, Math.min(20, numericCols.size()));
        List<String> finalFields = new ArrayList<>();
        for (String f : fields) if (numericCols.contains(f)) finalFields.add(f);
        if (finalFields.size() < 2) return Map.of("error", "Need >= 2 numeric fields for VIF.");

        List<double[]> mat = extractNumericMatrix(rows, finalFields);
        if (mat.size() < finalFields.size() + 2) return Map.of("error", "Not enough rows.");

        int n = mat.size(); int p = finalFields.size();
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < p; i++) {
            List<double[]> xs = new ArrayList<>();
            double[] ys = new double[n];
            for (int r = 0; r < n; r++) {
                ys[r] = mat.get(r)[i];
                double[] row = new double[p - 1]; int ci = 0;
                for (int j = 0; j < p; j++) { if (j != i) row[ci++] = mat.get(r)[j]; }
                xs.add(row);
            }
            double r2 = olsR2(xs, ys);
            double vif = r2 < 1.0 - 1e-12 ? 1.0 / (1.0 - r2) : 999.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", finalFields.get(i)); m.put("vif", Math.round(vif * 100.0) / 100.0);
            m.put("r2_with_others", r4(r2)); m.put("collinear", vif > policy.vifHighThreshold);
            results.add(m);
        }
        results.sort((a, b) -> Double.compare((double) b.get("vif"), (double) a.get("vif")));
        List<String> drop = new ArrayList<>();
        for (var r : results) if ((boolean) r.get("collinear")) drop.add((String) r.get("field"));
        return Map.of("fields", finalFields, "samples", n, "vif_results", results,
                "drop_candidates", drop, "threshold", policy.vifHighThreshold);
    }

    static double chi2Pvalue(double x, int dof) {
        if (x <= 0) return 1.0;
        double z = (Math.pow(x / dof, 1.0 / 3.0) - (1.0 - 2.0 / (9.0 * dof))) / Math.sqrt(2.0 / (9.0 * dof));
        return Math.max(0.0, Math.min(1.0, 1.0 - normalCdf(z)));
    }

    private static double r6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
    private static double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
