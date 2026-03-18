package com.binomialtechnologies.binomialhash.stats;

import java.util.*;

import static com.binomialtechnologies.binomialhash.stats.StatsHelpers.*;

/**
 * Temporal dynamics: autocorrelation, changepoints, rolling analysis, ergodicity.
 */
public final class DynamicsStats {

    private DynamicsStats() {}

    private static double[][] orderedValues(List<Map<String, Object>> rows, String field, String orderBy) {
        List<Object[]> paired = new ArrayList<>();
        for (var r : rows) {
            Double v = toFloatPermissive(r.get(field)); Object o = r.get(orderBy);
            if (v != null && o != null) paired.add(new Object[]{o, v});
        }
        paired.sort(Comparator.comparing(a -> String.valueOf(a[0])));
        double[] vals = new double[paired.size()];
        for (int i = 0; i < paired.size(); i++) vals[i] = (double) paired.get(i)[1];
        return new double[][]{vals};
    }

    public static Map<String, Object> autocorrelationDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String field, String orderBy, Integer maxLag, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        if (!colTypes.containsKey(field) || !colTypes.containsKey(orderBy))
            return Map.of("error", "Field(s) not found.");
        double[] vals = orderedValues(rows, field, orderBy)[0];
        int n = vals.length;
        if (n < policy.acfMinSamples) return Map.of("error", "Not enough values (" + n + ").");
        int ml = maxLag != null ? maxLag : Math.min(policy.acfMaxLag, n / 3);
        double mean = Arrays.stream(vals).sum() / n;
        double var = Arrays.stream(vals).map(v -> (v - mean) * (v - mean)).sum() / n;
        if (var < 1e-12) return Map.of("error", "Constant series.");
        double sigThresh = 2.0 / Math.sqrt(n);
        List<Map<String, Object>> acfVals = new ArrayList<>();
        List<Integer> sigLags = new ArrayList<>();
        for (int lag = 1; lag <= ml; lag++) {
            double cov = 0; for (int i = lag; i < n; i++) cov += (vals[i] - mean) * (vals[i - lag] - mean);
            double acf = (cov / n) / var;
            acfVals.add(Map.of("lag", lag, "acf", r4(acf)));
            if (Math.abs(acf) > sigThresh) sigLags.add(lag);
        }
        Integer domPeriod = null;
        for (int i = 1; i < acfVals.size() - 1; i++) {
            double cur = (double) acfVals.get(i).get("acf");
            double prev = (double) acfVals.get(i - 1).get("acf");
            double next = (double) acfVals.get(i + 1).get("acf");
            if (cur > prev && cur > next && cur > sigThresh) { domPeriod = (int) acfVals.get(i).get("lag"); break; }
        }
        double decay = acfVals.size() > 4 ? Math.abs((double) acfVals.get(0).get("acf") - (double) acfVals.get(Math.min(4, acfVals.size() - 1)).get("acf")) : 1;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("field", field); r.put("samples", n); r.put("acf_values", acfVals);
        r.put("significant_lags", sigLags); r.put("significance_threshold", r4(sigThresh));
        r.put("dominant_period", domPeriod); r.put("is_stationary_hint", decay > 0.3);
        return r;
    }

    public static Map<String, Object> changepointsDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String field, String orderBy, Integer minSegment, Double threshold, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        if (!colTypes.containsKey(field) || !colTypes.containsKey(orderBy))
            return Map.of("error", "Field(s) not found.");
        List<Object[]> paired = new ArrayList<>();
        for (var r : rows) {
            Double v = toFloatPermissive(r.get(field)); Object o = r.get(orderBy);
            if (v != null && o != null) paired.add(new Object[]{o, v});
        }
        paired.sort(Comparator.comparing(a -> String.valueOf(a[0])));
        int n = paired.size();
        if (n < 20) return Map.of("error", "Not enough values.");
        double[] vals = new double[n]; Object[] order = new Object[n];
        for (int i = 0; i < n; i++) { vals[i] = (double) paired.get(i)[1]; order[i] = paired.get(i)[0]; }
        int ms = minSegment != null ? minSegment : policy.changepointMinSegment;
        double thresh = threshold != null ? threshold : policy.changepointDefaultThreshold;
        double mean = Arrays.stream(vals).sum() / n;
        double std = Math.sqrt(Arrays.stream(vals).map(v -> (v - mean) * (v - mean)).sum() / n);
        if (std < 1e-12) return Map.of("field", field, "samples", n, "changepoints", List.of());
        List<Map<String, Object>> cps = new ArrayList<>();
        int i = ms;
        while (i < n - ms) {
            double mBefore = 0, mAfter = 0;
            for (int j = Math.max(0, i - ms); j < i; j++) mBefore += vals[j];
            mBefore /= ms;
            for (int j = i; j < Math.min(n, i + ms); j++) mAfter += vals[j];
            mAfter /= Math.min(ms, n - i);
            double mag = Math.abs(mAfter - mBefore) / std;
            if (mag > thresh) {
                cps.add(Map.of("index", i, "order_value", String.valueOf(order[i]),
                        "mean_before", r4(mBefore), "mean_after", r4(mAfter),
                        "magnitude", r4(mag), "confidence", r4(Math.min(1.0, mag / (thresh * 2)))));
                i += ms;
            } else i++;
        }
        return Map.of("field", field, "samples", n, "changepoints", cps, "global_mean", r4(mean), "global_std", r4(std));
    }

    public static Map<String, Object> ergodTestDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String field, String orderBy, List<Integer> windowSizes, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        if (!colTypes.containsKey(field) || !colTypes.containsKey(orderBy))
            return Map.of("error", "Field(s) not found.");
        double[] vals = orderedValues(rows, field, orderBy)[0];
        int n = vals.length;
        if (n < 30) return Map.of("error", "Not enough values.");
        if (windowSizes == null || windowSizes.isEmpty()) windowSizes = List.of(10, 25, 50, 100);
        List<Integer> ws = new ArrayList<>(); for (int w : windowSizes) if (w < n) ws.add(w);
        double ensMean = Arrays.stream(vals).sum() / n;
        double ensVar = Arrays.stream(vals).map(v -> (v - ensMean) * (v - ensMean)).sum() / n;
        List<Map<String, Object>> timeAvgs = new ArrayList<>();
        for (int w : ws) {
            List<Double> chunkMeans = new ArrayList<>();
            for (int i = 0; i <= n - w; i += Math.max(1, w / 2)) {
                double s = 0; for (int j = i; j < i + w; j++) s += vals[j];
                chunkMeans.add(s / w);
            }
            double spread = 0;
            if (!chunkMeans.isEmpty()) {
                double cmMean = chunkMeans.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                spread = Math.sqrt(chunkMeans.stream().mapToDouble(m -> (m - ensMean) * (m - ensMean)).sum() / chunkMeans.size());
                timeAvgs.add(Map.of("window", w, "mean_of_means", r4(cmMean), "spread_of_means", r4(spread), "n_chunks", chunkMeans.size()));
            }
        }
        double ergoRatio = 1.0;
        if (timeAvgs.size() >= 2) {
            double s0 = (double) timeAvgs.get(0).get("spread_of_means");
            double sLast = (double) timeAvgs.get(timeAvgs.size() - 1).get("spread_of_means");
            if (s0 > 1e-12) {
                double expected = Math.sqrt((double) ws.get(0) / ws.get(ws.size() - 1));
                double actual = sLast / s0;
                ergoRatio = expected > 1e-12 ? actual / expected : 1;
            }
        }
        boolean ergodic = ergoRatio > 0.5 && ergoRatio < 2.0;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("field", field); r.put("samples", n);
        r.put("ensemble_mean", r4(ensMean)); r.put("ensemble_std", r4(Math.sqrt(ensVar)));
        r.put("time_averages_by_window", timeAvgs); r.put("ergodicity_ratio", r4(ergoRatio));
        r.put("is_ergodic", ergodic);
        return r;
    }

    private static double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
