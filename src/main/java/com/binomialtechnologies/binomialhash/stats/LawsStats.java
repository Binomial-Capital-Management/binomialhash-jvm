package com.binomialtechnologies.binomialhash.stats;

import java.util.*;

import static com.binomialtechnologies.binomialhash.stats.StatsHelpers.*;

/**
 * Scale, symmetry, and universal laws:
 * entropy spectrum, symmetry scan.
 */
public final class LawsStats {

    private LawsStats() {}

    /** Multi-scale sample entropy (complexity profile). */
    public static Map<String, Object> entropySpectrumDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String field, String orderBy, Integer maxScale, Integer embeddingDim, StatsPolicy policy) {
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
        if (n < 50) return Map.of("error", "Not enough values (" + n + ").");
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) vals[i] = (double) paired.get(i)[1];

        int ms = maxScale != null ? maxScale : policy.entropyMaxScale;
        int m = embeddingDim != null ? embeddingDim : policy.entropyDefaultEmbed;
        double mean = Arrays.stream(vals).sum() / n;
        double std = Math.sqrt(Arrays.stream(vals).map(v -> (v - mean) * (v - mean)).sum() / n);
        double rTol = std > 1e-12 ? 0.15 * std : 0.15;

        List<Map<String, Object>> spectrum = new ArrayList<>();
        for (int scale = 1; scale <= ms; scale++) {
            int cn = n / scale;
            if (cn < m + 5) break;
            double[] coarsened = new double[cn];
            for (int i = 0; i < cn; i++) {
                double s = 0; for (int j = i * scale; j < (i + 1) * scale; j++) s += vals[j];
                coarsened[i] = s / scale;
            }
            double se = sampleEntropy(coarsened, m, rTol);
            spectrum.add(Map.of("scale", scale, "sample_entropy", r4(se), "series_length", cn));
        }

        String ctype = "insufficient_data";
        if (spectrum.size() >= 3) {
            List<Double> ents = new ArrayList<>();
            for (var s : spectrum) ents.add((double) s.get("sample_entropy"));
            double first = ents.get(0), last = ents.get(ents.size() - 1);
            double maxE = Collections.max(ents), minE = Collections.min(ents);
            if (first > last * 1.5) ctype = "decreasing";
            else if (last > first * 1.5) ctype = "increasing";
            else if (maxE / (minE + 1e-12) < 1.3) ctype = "scale_invariant";
            else ctype = "mixed";
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("field", field); r.put("samples", n); r.put("embedding_dim", m);
        r.put("tolerance", r4(rTol)); r.put("entropy_spectrum", spectrum);
        r.put("complexity_type", ctype);
        return r;
    }

    /** Detect invariances: translation, scaling, reflection, permutation symmetries. */
    public static Map<String, Object> symmetryScanDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            List<String> fields, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        List<String> numCols = new ArrayList<>();
        for (var e : colTypes.entrySet()) if ("numeric".equals(e.getValue())) numCols.add(e.getKey());
        if (fields == null || fields.isEmpty()) fields = numCols.subList(0, Math.min(15, numCols.size()));
        Set<String> allowed = new HashSet<>(numCols);
        List<String> ff = new ArrayList<>(); for (String f : fields) if (allowed.contains(f)) ff.add(f);
        if (ff.size() < 2) return Map.of("error", "Need >= 2 fields.");
        List<double[]> mat = extractNumericMatrix(rows, ff);
        int n = mat.size();
        if (n < 20) return Map.of("error", "Not enough rows.");
        int p = ff.size();
        List<Map<String, Object>> symmetries = new ArrayList<>();

        for (int i = 0; i < p; i++) {
            double[] col = colArr(mat, i, n);
            double m = mean(col); double s = std(col, m);
            if (s < 1e-12) continue;
            double skew = 0; for (double v : col) { double z = (v - m) / s; skew += z * z * z; } skew /= n;
            if (Math.abs(skew) < 0.2) symmetries.add(Map.of("type", "reflection_symmetry", "field", ff.get(i), "skewness", r4(skew)));
        }

        for (int i = 0; i < p; i++) {
            for (int j = i + 1; j < p; j++) {
                double[] ci = colArr(mat, i, n); double[] cj = colArr(mat, j, n);
                Arrays.sort(ci); Arrays.sort(cj);
                double maxAbs = 1e-12;
                for (double v : ci) maxAbs = Math.max(maxAbs, Math.abs(v));
                for (double v : cj) maxAbs = Math.max(maxAbs, Math.abs(v));
                double ks = 0;
                for (int k = 0; k < n; k++) ks = Math.max(ks, Math.abs(ci[k] - cj[k]));
                ks /= maxAbs;
                if (ks < 0.1) symmetries.add(Map.of("type", "permutation_symmetry", "field_a", ff.get(i), "field_b", ff.get(j), "ks_distance", r4(ks)));
            }
        }

        return Map.of("fields", ff, "samples", n, "symmetries", symmetries, "total_symmetries_found", symmetries.size());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static double sampleEntropy(double[] series, int m, double rVal) {
        int ns = series.length;
        if (ns < m + 2) return 0;
        int bCount = countMatches(series, m, rVal);
        int aCount = countMatches(series, m + 1, rVal);
        if (bCount == 0) return 0;
        return aCount > 0 ? -Math.log((double) aCount / bCount) : 0;
    }

    private static int countMatches(double[] series, int dim, double rVal) {
        int ns = series.length; int count = 0;
        for (int i = 0; i < ns - dim; i++)
            for (int j = i + 1; j < ns - dim; j++) {
                boolean match = true;
                for (int k = 0; k < dim; k++) if (Math.abs(series[i + k] - series[j + k]) > rVal) { match = false; break; }
                if (match) count++;
            }
        return count;
    }

    private static double[] colArr(List<double[]> mat, int j, int n) {
        double[] r = new double[n]; for (int i = 0; i < n; i++) r[i] = mat.get(i)[j]; return r;
    }

    private static double mean(double[] v) { double s = 0; for (double x : v) s += x; return s / v.length; }
    private static double std(double[] v, double m) { double s = 0; for (double x : v) s += (x - m) * (x - m); return Math.sqrt(s / v.length); }
    private static double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
