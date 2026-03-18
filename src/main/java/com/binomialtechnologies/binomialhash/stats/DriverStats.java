package com.binomialtechnologies.binomialhash.stats;

import java.util.*;

import static com.binomialtechnologies.binomialhash.stats.StatsHelpers.*;

/**
 * Driver discovery and feature selection:
 * polynomial test, interaction screen, feature importance, information bottleneck.
 */
public final class DriverStats {

    private DriverStats() {}

    public static Map<String, Object> polynomialTestDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String fieldX, String fieldY, int maxDegree, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        double[][] pair = extractNumericPairs(rows, fieldX, fieldY);
        double[] xs = pair[0], ys = pair[1]; int n = xs.length;
        if (n < policy.polynomialMinSamples) return Map.of("error", "Not enough values (" + n + ").");
        maxDegree = Math.min(maxDegree, 3);

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("field_x", fieldX); results.put("field_y", fieldY); results.put("samples", n);
        for (int deg = 1; deg <= maxDegree; deg++) {
            if (n < deg + 2) break;
            List<double[]> xsPoly = new ArrayList<>();
            for (double x : xs) {
                double[] row = new double[deg];
                for (int p = 0; p < deg; p++) row[p] = Math.pow(x, p + 1);
                xsPoly.add(row);
            }
            double r2 = olsR2(xsPoly, ys);
            results.put("degree_" + deg + "_r2", r4(r2));
        }
        double linear = results.containsKey("degree_1_r2") ? (double) results.get("degree_1_r2") : 0;
        int bestDeg = 1; double bestR2 = linear;
        for (int deg = 2; deg <= maxDegree; deg++) {
            Object v = results.get("degree_" + deg + "_r2");
            if (v != null && (double) v > bestR2 + 0.02) { bestDeg = deg; bestR2 = (double) v; }
        }
        results.put("best_degree", bestDeg);
        results.put("curvature_gain", r4(bestR2 - linear));
        return results;
    }

    public static Map<String, Object> interactionScreenDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String target, List<String> candidates, Integer topK, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        List<String> numericCols = numCols(colTypes);
        if (!numericCols.contains(target)) return Map.of("error", "Target must be numeric.");
        List<String> cands = new ArrayList<>();
        for (String c : candidates) if (numericCols.contains(c) && !c.equals(target)) cands.add(c);
        if (cands.size() < 2) return Map.of("error", "Need >= 2 candidates.");
        if (topK == null) topK = policy.interactionDefaultTopK;
        List<String> allFields = new ArrayList<>(); allFields.add(target); allFields.addAll(cands);
        List<double[]> mat = extractNumericMatrix(rows, allFields);
        int n = mat.size();
        if (n < policy.interactionMinSamples) return Map.of("error", "Not enough rows (" + n + ").");
        double[] ys = col(mat, 0, n);
        List<Map<String, Object>> interactions = new ArrayList<>();
        for (int i = 0; i < cands.size(); i++) {
            for (int j = i + 1; j < cands.size(); j++) {
                int ci = i + 1, cj = j + 1;
                List<double[]> xsA = new ArrayList<>(), xsB = new ArrayList<>(), xsAdd = new ArrayList<>(), xsAB = new ArrayList<>();
                for (int r = 0; r < n; r++) {
                    xsA.add(new double[]{mat.get(r)[ci]});
                    xsB.add(new double[]{mat.get(r)[cj]});
                    xsAdd.add(new double[]{mat.get(r)[ci], mat.get(r)[cj]});
                    xsAB.add(new double[]{mat.get(r)[ci], mat.get(r)[cj], mat.get(r)[ci] * mat.get(r)[cj]});
                }
                double r2A = olsR2(xsA, ys), r2B = olsR2(xsB, ys);
                double r2Add = olsR2(xsAdd, ys), r2Joint = olsR2(xsAB, ys);
                double strength = r2Joint - r2Add;
                String itype;
                if (r2Joint > r2A && r2Joint > r2B) itype = strength > 0.02 ? "synergy" : "additive";
                else itype = strength < -0.02 ? "suppression" : "additive";
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field_a", cands.get(i)); m.put("field_b", cands.get(j));
                m.put("r2_a", r4(r2A)); m.put("r2_b", r4(r2B)); m.put("r2_joint", r4(r2Joint));
                m.put("interaction_strength", r4(strength)); m.put("interaction_type", itype);
                interactions.add(m);
            }
        }
        interactions.sort((a, b) -> Double.compare(Math.abs((double) b.get("interaction_strength")),
                Math.abs((double) a.get("interaction_strength"))));
        return Map.of("target", target, "samples", n,
                "interactions", interactions.subList(0, Math.min(topK, interactions.size())),
                "total_pairs_tested", interactions.size());
    }

    public static Map<String, Object> featureImportanceDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String target, List<String> candidates, Integer nShuffles, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        List<String> numericCols = numCols(colTypes);
        if (!numericCols.contains(target)) return Map.of("error", "Target must be numeric.");
        List<String> cands = new ArrayList<>();
        for (String c : candidates) if (numericCols.contains(c) && !c.equals(target)) cands.add(c);
        if (cands.isEmpty()) return Map.of("error", "No valid candidates.");
        if (nShuffles == null) nShuffles = policy.importanceNShuffles;
        List<String> allFields = new ArrayList<>(); allFields.add(target); allFields.addAll(cands);
        List<double[]> mat = extractNumericMatrix(rows, allFields);
        int n = mat.size();
        if (n < 15) return Map.of("error", "Not enough rows (" + n + ").");
        double[] ys = col(mat, 0, n);
        List<double[]> xsAll = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            double[] row = new double[cands.size()];
            System.arraycopy(mat.get(r), 1, row, 0, cands.size());
            xsAll.add(row);
        }
        double baseline = olsR2(xsAll, ys);
        Random rng = new Random(42);
        List<Map<String, Object>> results = new ArrayList<>();
        for (int j = 0; j < cands.size(); j++) {
            double dropSum = 0;
            for (int s = 0; s < nShuffles; s++) {
                List<double[]> shuffled = new ArrayList<>();
                for (double[] row : xsAll) shuffled.add(row.clone());
                double[] colVals = new double[n];
                for (int r = 0; r < n; r++) colVals[r] = shuffled.get(r)[j];
                for (int i = n - 1; i > 0; i--) {
                    int k = rng.nextInt(i + 1);
                    double tmp = colVals[i]; colVals[i] = colVals[k]; colVals[k] = tmp;
                }
                for (int r = 0; r < n; r++) shuffled.get(r)[j] = colVals[r];
                dropSum += baseline - olsR2(shuffled, ys);
            }
            results.add(Map.of("field", cands.get(j), "importance_score", r6(dropSum / nShuffles),
                    "baseline_r2", r4(baseline)));
        }
        results.sort((a, b) -> Double.compare((double) b.get("importance_score"), (double) a.get("importance_score")));
        return Map.of("target", target, "samples", n, "baseline_r2", r4(baseline), "importances", results);
    }

    public static Map<String, Object> informationBottleneckDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            List<String> inputFields, String targetField, Double beta, Integer nClusters, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        List<String> numericCols = numCols(colTypes);
        if (!numericCols.contains(targetField)) return Map.of("error", "Target must be numeric.");
        List<String> ff = new ArrayList<>();
        for (String f : inputFields) if (numericCols.contains(f) && !f.equals(targetField)) ff.add(f);
        if (ff.isEmpty()) return Map.of("error", "No valid input fields.");
        if (beta == null) beta = policy.ibDefaultBeta;
        int nc = nClusters != null ? nClusters : policy.ibDefaultClusters;
        int bins = 8;
        List<String> allFields = new ArrayList<>(ff); allFields.add(targetField);
        List<double[]> mat = extractNumericMatrix(rows, allFields);
        int n = mat.size();
        if (n < 20) return Map.of("error", "Not enough rows (" + n + ").");
        int nInput = ff.size();

        int[][] inputBins = new int[nInput][n];
        for (int j = 0; j < nInput; j++) {
            double[] cv = col(mat, j, n); double[] sorted = cv.clone(); Arrays.sort(sorted);
            double[] edges = quantileEdges(sorted, bins);
            for (int r = 0; r < n; r++) inputBins[j][r] = bucketIndex(mat.get(r)[j], edges);
        }
        double[] tv = col(mat, nInput, n); double[] tvs = tv.clone(); Arrays.sort(tvs);
        double[] tEdges = quantileEdges(tvs, bins);
        int[] targetBins = new int[n];
        for (int r = 0; r < n; r++) targetBins[r] = bucketIndex(mat.get(r)[nInput], tEdges);

        Map<List<Integer>, Integer> stateMap = new LinkedHashMap<>();
        int[] states = new int[n];
        for (int r = 0; r < n; r++) {
            List<Integer> key = new ArrayList<>();
            for (int j = 0; j < nInput; j++) key.add(inputBins[j][r]);
            stateMap.putIfAbsent(key, stateMap.size());
            states[r] = stateMap.get(key);
        }
        int nStates = stateMap.size();

        double[] px = new double[nStates]; double[][] pyx = new double[nStates][bins];
        for (int r = 0; r < n; r++) { px[states[r]]++; pyx[states[r]][targetBins[r]]++; }
        for (int s = 0; s < nStates; s++) { if (px[s] > 0) for (int t = 0; t < bins; t++) pyx[s][t] /= px[s]; }
        double totalPx = 0; for (double p : px) totalPx += p;
        for (int s = 0; s < nStates; s++) px[s] /= totalPx;

        int[] assignments = new int[nStates];
        for (int s = 0; s < nStates; s++) assignments[s] = s % nc;
        for (int iter = 0; iter < policy.ibMaxIter; iter++) {
            double[] pc = new double[nc]; double[][] pyc = new double[nc][bins];
            for (int s = 0; s < nStates; s++) {
                int c = assignments[s]; pc[c] += px[s];
                for (int t = 0; t < bins; t++) pyc[c][t] += px[s] * pyx[s][t];
            }
            for (int c = 0; c < nc; c++) if (pc[c] > 0) for (int t = 0; t < bins; t++) pyc[c][t] /= pc[c];
            boolean changed = false;
            for (int s = 0; s < nStates; s++) {
                if (px[s] < 1e-12) continue;
                int bestC = assignments[s]; double bestCost = Double.MAX_VALUE;
                for (int c = 0; c < nc; c++) {
                    double kl = 0;
                    for (int t = 0; t < bins; t++)
                        if (pyx[s][t] > 1e-12 && pyc[c][t] > 1e-12) kl += pyx[s][t] * Math.log(pyx[s][t] / pyc[c][t]);
                    double cost = kl - (1.0 / beta) * Math.log(Math.max(pc[c], 1e-12));
                    if (cost < bestCost) { bestCost = cost; bestC = c; }
                }
                if (bestC != assignments[s]) { assignments[s] = bestC; changed = true; }
            }
            if (!changed) break;
        }

        int[] rowClusters = new int[n];
        for (int r = 0; r < n; r++) rowClusters[r] = assignments[states[r]];
        int[] pcFinal = new int[nc]; int[][] pycFinal = new int[nc][bins];
        for (int r = 0; r < n; r++) { pcFinal[rowClusters[r]]++; pycFinal[rowClusters[r]][targetBins[r]]++; }
        double hT = shannonEntropy(pcFinal);
        int[] pt = new int[bins]; for (int r = 0; r < n; r++) pt[targetBins[r]]++;
        double hY = shannonEntropy(pt);
        int[] jointFlat = new int[nc * bins]; int idx = 0;
        for (int c = 0; c < nc; c++) for (int t = 0; t < bins; t++) jointFlat[idx++] = pycFinal[c][t];
        double hTY = shannonEntropy(jointFlat);
        double miTY = Math.max(0, hT + hY - hTY);

        int[] stateTarget = new int[nStates * bins];
        for (int r = 0; r < n; r++) stateTarget[states[r] * bins + targetBins[r]]++;
        double hXY = shannonEntropy(stateTarget);
        int[] stateCounts = new int[nStates]; for (int r = 0; r < n; r++) stateCounts[states[r]]++;
        double hX = shannonEntropy(stateCounts);
        double miXY = Math.max(0, hX + hY - hXY);
        double preserved = miXY > 1e-12 ? miTY / miXY : 0;

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("input_fields", ff); res.put("target", targetField);
        res.put("samples", n); res.put("n_clusters", nc); res.put("beta", beta);
        res.put("preserved_info", r4(preserved));
        res.put("compression_ratio", Math.round((double) nStates / Math.max(nc, 1) * 100.0) / 100.0);
        res.put("mi_compressed_target", r6(miTY)); res.put("mi_full_target", r6(miXY));
        return res;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<String> numCols(Map<String, String> colTypes) {
        List<String> r = new ArrayList<>();
        for (var e : colTypes.entrySet()) if ("numeric".equals(e.getValue())) r.add(e.getKey());
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
