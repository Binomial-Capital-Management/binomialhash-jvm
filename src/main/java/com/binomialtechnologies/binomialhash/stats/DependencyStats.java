package com.binomialtechnologies.binomialhash.stats;

import java.util.*;

import static com.binomialtechnologies.binomialhash.stats.StatsHelpers.*;

/**
 * Dependency mapping and independence tests:
 * rank correlation, chi-squared, ANOVA, mutual information, copula tail.
 */
public final class DependencyStats {

    private DependencyStats() {}

    public static Map<String, Object> rankCorrDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            List<String> fields, String method, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        if (method == null) method = "spearman";
        List<String> numericCols = numCols(colTypes);
        if (fields == null || fields.isEmpty()) fields = numericCols.subList(0, Math.min(15, numericCols.size()));
        List<String> ff = filter(fields, numericCols);
        if (ff.size() < 2) return Map.of("error", "Need >= 2 numeric fields.");
        List<double[]> mat = extractNumericMatrix(rows, ff);
        int n = mat.size();
        if (n < policy.rankCorrMinSamples) return Map.of("error", "Not enough rows (" + n + ").");

        List<Map<String, Object>> pairs = new ArrayList<>();
        for (int i = 0; i < ff.size(); i++) {
            for (int j = i + 1; j < ff.size(); j++) {
                double[] xs = col(mat, i, n), ys = col(mat, j, n);
                double pCorr = pearsonCorr(xs, ys);
                double sCorr = pearsonCorr(spearmanRank(xs), spearmanRank(ys));
                double div = Math.abs(sCorr - pCorr);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field_a", ff.get(i)); m.put("field_b", ff.get(j));
                m.put("spearman", r4(sCorr)); m.put("pearson", r4(pCorr)); m.put("divergence", r4(div));
                pairs.add(m);
            }
        }
        pairs.sort((a, b) -> Double.compare((double) b.get("divergence"), (double) a.get("divergence")));
        List<Map<String, Object>> nonlinear = new ArrayList<>();
        for (var p : pairs) if ((double) p.get("divergence") > 0.15) nonlinear.add(p);
        return Map.of("fields", ff, "samples", n, "method", method, "pairs", pairs, "nonlinear_pairs", nonlinear);
    }

    public static Map<String, Object> chiSquaredDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String fieldA, String fieldB, Integer bins, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        if (!colTypes.containsKey(fieldA) || !colTypes.containsKey(fieldB))
            return Map.of("error", "Field(s) not found.");
        if (bins == null) bins = policy.chiSquaredDefaultBins;
        List<Object> valsA = new ArrayList<>(), valsB = new ArrayList<>();
        for (var r : rows) {
            Object a = r.get(fieldA), b = r.get(fieldB);
            if (a != null && b != null) { valsA.add(a); valsB.add(b); }
        }
        int n = valsA.size();
        if (n < 10) return Map.of("error", "Not enough paired values (" + n + ").");
        List<String> catA = categorize(valsA, bins, n), catB = categorize(valsB, bins, n);
        List<String> labA = new ArrayList<>(new TreeSet<>(catA)), labB = new ArrayList<>(new TreeSet<>(catB));
        int ra = labA.size(), rb = labB.size();
        if (ra < 2 || rb < 2) return Map.of("error", "Each field must have >= 2 categories.");
        Map<String, Integer> idxA = new HashMap<>(), idxB = new HashMap<>();
        for (int i = 0; i < ra; i++) idxA.put(labA.get(i), i);
        for (int i = 0; i < rb; i++) idxB.put(labB.get(i), i);
        int[][] table = new int[ra][rb];
        for (int i = 0; i < n; i++) table[idxA.get(catA.get(i))][idxB.get(catB.get(i))]++;
        int[] rowSums = new int[ra]; int[] colSums = new int[rb];
        for (int i = 0; i < ra; i++) for (int j = 0; j < rb; j++) { rowSums[i] += table[i][j]; colSums[j] += table[i][j]; }
        double chi2 = 0;
        for (int i = 0; i < ra; i++) for (int j = 0; j < rb; j++) {
            double exp = (double) rowSums[i] * colSums[j] / n;
            if (exp > 0) chi2 += (table[i][j] - exp) * (table[i][j] - exp) / exp;
        }
        int dof = (ra - 1) * (rb - 1);
        double pValue = dof > 0 ? QualityStats.chi2Pvalue(chi2, dof) : 1.0;
        double cramersV = n > 0 && Math.min(ra, rb) > 1 ? Math.sqrt(chi2 / (n * (Math.min(ra, rb) - 1))) : 0;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("field_a", fieldA); res.put("field_b", fieldB); res.put("samples", n);
        res.put("chi_squared", r4(chi2)); res.put("dof", dof); res.put("p_value", r6(pValue));
        res.put("cramers_v", r4(cramersV)); res.put("categories_a", ra); res.put("categories_b", rb);
        return res;
    }

    public static Map<String, Object> anovaDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String groupField, String targetField, StatsPolicy policy) {
        final StatsPolicy pol = policy != null ? policy : StatsPolicy.DEFAULT;
        if (!colTypes.containsKey(groupField) || !colTypes.containsKey(targetField))
            return Map.of("error", "Field(s) not found.");
        Map<String, List<Double>> groups = new LinkedHashMap<>();
        for (var r : rows) {
            Object g = r.get(groupField); Double v = toFloatPermissive(r.get(targetField));
            if (g != null && v != null) groups.computeIfAbsent(String.valueOf(g), k -> new ArrayList<>()).add(v);
        }
        groups.entrySet().removeIf(e -> e.getValue().size() < pol.anovaMinGroupSize);
        if (groups.size() < pol.anovaMinGroups) return Map.of("error", "Not enough groups.");
        List<Double> all = new ArrayList<>(); groups.values().forEach(all::addAll);
        double grandMean = all.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int nTotal = all.size(); int k = groups.size();
        double ssBetween = 0, ssWithin = 0;
        for (var vs : groups.values()) {
            double gMean = vs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            ssBetween += vs.size() * (gMean - grandMean) * (gMean - grandMean);
            for (double v : vs) ssWithin += (v - gMean) * (v - gMean);
        }
        int dfBetween = k - 1, dfWithin = nTotal - k;
        if (dfWithin <= 0 || dfBetween <= 0) return Map.of("error", "Not enough DOF.");
        double msBetween = ssBetween / dfBetween, msWithin = ssWithin / dfWithin;
        double fStat = msWithin > 1e-12 ? msBetween / msWithin : 0;
        double ssTotal = ssBetween + ssWithin;
        double etaSq = ssTotal > 1e-12 ? ssBetween / ssTotal : 0;
        double pValue = fPvalue(fStat, dfBetween, dfWithin);
        List<Map<String, Object>> gs = new ArrayList<>();
        for (var e : new TreeMap<>(groups).entrySet()) {
            double m = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double s = Math.sqrt(e.getValue().stream().mapToDouble(v -> (v - m) * (v - m)).sum() / e.getValue().size());
            gs.add(Map.of("group", e.getKey(), "n", e.getValue().size(), "mean", r4(m), "std", r4(s)));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("group_field", groupField); res.put("target_field", targetField);
        res.put("n_groups", k); res.put("samples", nTotal);
        res.put("f_statistic", r4(fStat)); res.put("p_value", r6(pValue));
        res.put("eta_squared", r4(etaSq)); res.put("group_stats", gs);
        return res;
    }

    public static Map<String, Object> mutualInfoMatrixDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            List<String> fields, Integer bins, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        List<String> numericCols = numCols(colTypes);
        if (fields == null || fields.isEmpty()) fields = numericCols.subList(0, Math.min(15, numericCols.size()));
        List<String> ff = filter(fields, numericCols);
        if (ff.size() < 2) return Map.of("error", "Need >= 2 numeric fields.");
        if (bins == null) bins = policy.miDefaultBins;
        List<double[]> mat = extractNumericMatrix(rows, ff);
        int n = mat.size();
        if (n < policy.miMinSamples) return Map.of("error", "Not enough rows (" + n + ").");
        int B = bins;
        double[][] colEdges = new double[ff.size()][];
        for (int i = 0; i < ff.size(); i++) {
            double[] cv = col(mat, i, n); Arrays.sort(cv);
            colEdges[i] = quantileEdges(cv, B);
        }
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (int i = 0; i < ff.size(); i++) {
            for (int j = i + 1; j < ff.size(); j++) {
                int[][] joint = new int[B][B];
                for (int r = 0; r < n; r++) {
                    int bi = bucketIndex(mat.get(r)[i], colEdges[i]);
                    int bj = bucketIndex(mat.get(r)[j], colEdges[j]);
                    joint[bi][bj]++;
                }
                int[] margI = new int[B], margJ = new int[B];
                for (int bi = 0; bi < B; bi++) for (int bj = 0; bj < B; bj++) { margI[bi] += joint[bi][bj]; margJ[bj] += joint[bi][bj]; }
                int[] jointFlat = new int[B * B]; int idx = 0;
                for (int bi = 0; bi < B; bi++) for (int bj = 0; bj < B; bj++) jointFlat[idx++] = joint[bi][bj];
                double hI = shannonEntropy(margI), hJ = shannonEntropy(margJ), hIJ = shannonEntropy(jointFlat);
                double mi = Math.max(0, hI + hJ - hIJ);
                double pCorr = Math.abs(pearsonCorr(col(mat, i, n), col(mat, j, n)));
                double nmi = Math.min(hI, hJ) > 1e-12 ? mi / Math.min(hI, hJ) : 0;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field_a", ff.get(i)); m.put("field_b", ff.get(j));
                m.put("mutual_info", r6(mi)); m.put("normalized_mi", r4(nmi));
                m.put("pearson_abs", r4(pCorr)); m.put("nonlinear_flag", nmi > 0.1 && nmi > pCorr * 1.5);
                pairs.add(m);
            }
        }
        pairs.sort((a, b) -> Double.compare((double) b.get("mutual_info"), (double) a.get("mutual_info")));
        List<Map<String, Object>> nonlinear = new ArrayList<>();
        for (var p : pairs) if ((boolean) p.get("nonlinear_flag")) nonlinear.add(p);
        return Map.of("fields", ff, "samples", n, "bins", B, "pairs", pairs, "nonlinear_pairs", nonlinear);
    }

    public static Map<String, Object> copulaTailDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String fieldA, String fieldB, Double tailThreshold, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        double[][] pair = extractNumericPairs(rows, fieldA, fieldB);
        double[] xs = pair[0], ys = pair[1]; int n = xs.length;
        if (n < policy.copulaMinSamples) return Map.of("error", "Not enough values (" + n + ").");
        double tail = tailThreshold != null ? tailThreshold : policy.copulaDefaultTail;
        double[] rx = spearmanRank(xs), ry = spearmanRank(ys);
        double[] ux = new double[n], uy = new double[n];
        for (int i = 0; i < n; i++) { ux[i] = rx[i] / (n + 1); uy[i] = ry[i] / (n + 1); }
        int uc = 0, lc = 0;
        for (int i = 0; i < n; i++) {
            if (ux[i] > 1 - tail && uy[i] > 1 - tail) uc++;
            if (ux[i] < tail && uy[i] < tail) lc++;
        }
        double upperTail = n * tail > 0 ? (double) uc / (n * tail) : 0;
        double lowerTail = n * tail > 0 ? (double) lc / (n * tail) : 0;
        int concordant = 0, discordant = 0;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < Math.min(i + 500, n); j++) {
            double dx = xs[i] - xs[j], dy = ys[i] - ys[j];
            if (dx * dy > 0) concordant++; else if (dx * dy < 0) discordant++;
        }
        int totalPairs = concordant + discordant;
        double kendall = totalPairs > 0 ? (double) (concordant - discordant) / totalPairs : 0;

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("field_a", fieldA); res.put("field_b", fieldB); res.put("samples", n);
        res.put("upper_tail_dependence", r4(upperTail)); res.put("lower_tail_dependence", r4(lowerTail));
        res.put("tail_asymmetry", r4(upperTail - lowerTail)); res.put("kendall_tau", r4(kendall));
        res.put("spearman_rho", r4(pearsonCorr(spearmanRank(xs), spearmanRank(ys))));
        res.put("pearson_r", r4(pearsonCorr(xs, ys)));
        return res;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static double fPvalue(double f, int df1, int df2) {
        if (f <= 0 || df1 <= 0 || df2 <= 0) return 1.0;
        double a = 2.0 / (9.0 * df1), b = 2.0 / (9.0 * df2);
        double z = ((1.0 - b) * Math.pow(f, 1.0 / 3.0) - (1.0 - a)) / Math.sqrt(b * Math.pow(f, 2.0 / 3.0) + a);
        return Math.max(0.0, Math.min(1.0, 1.0 - normalCdf(z)));
    }

    private static List<String> categorize(List<Object> vals, int bins, int n) {
        List<Double> nums = new ArrayList<>();
        for (Object v : vals) { Double d = toFloatPermissive(v); nums.add(d); }
        long numCount = nums.stream().filter(Objects::nonNull).count();
        if (numCount > n * 0.8) {
            double[] clean = nums.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).sorted().toArray();
            double[] edges = quantileEdges(clean, bins);
            List<String> cats = new ArrayList<>();
            for (Double d : nums) cats.add(d != null ? String.valueOf(bucketIndex(d, edges)) : "NA");
            return cats;
        }
        List<String> cats = new ArrayList<>();
        for (Object v : vals) cats.add(String.valueOf(v));
        return cats;
    }

    private static List<String> numCols(Map<String, String> colTypes) {
        List<String> r = new ArrayList<>();
        for (var e : colTypes.entrySet()) if ("numeric".equals(e.getValue())) r.add(e.getKey());
        return r;
    }

    private static List<String> filter(List<String> fields, List<String> allowed) {
        List<String> r = new ArrayList<>();
        Set<String> set = new HashSet<>(allowed);
        for (String f : fields) if (set.contains(f)) r.add(f);
        return r;
    }

    private static double[] col(List<double[]> mat, int j, int n) {
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = mat.get(i)[j];
        return r;
    }

    private static double r6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
    private static double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
