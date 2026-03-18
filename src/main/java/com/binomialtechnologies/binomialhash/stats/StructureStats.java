package com.binomialtechnologies.binomialhash.stats;

import java.util.*;

import static com.binomialtechnologies.binomialhash.stats.StatsHelpers.*;

/**
 * Structure and topology analysis (pure-Java subset):
 * K-means clustering, persistent topology via union-find.
 */
public final class StructureStats {

    private StructureStats() {}

    /** K-means clustering with auto-k via silhouette scoring. */
    public static Map<String, Object> clusterDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            List<String> fields, Integer k, Integer maxK, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        List<String> numCols = numericCols(colTypes);
        if (fields == null || fields.isEmpty()) fields = numCols.subList(0, Math.min(15, numCols.size()));
        List<String> ff = filterFields(fields, numCols);
        if (ff.size() < 2) return Map.of("error", "Need >= 2 numeric fields.");
        List<double[]> mat = extractNumericMatrix(rows, ff);
        int n = mat.size();
        if (n < 10) return Map.of("error", "Not enough rows (" + n + ").");
        int maxKVal = maxK != null ? maxK : policy.clusterMaxK;

        double[] mu = colMean(mat, ff.size(), n);
        double[] std = colStd(mat, mu, ff.size(), n);
        List<double[]> xn = normalize(mat, mu, std, ff.size());

        int bestK; int[][] bestLabels; double[][] bestCentroids; double bestSil;
        if (k != null) {
            bestK = Math.min(Math.max(k, 2), Math.min(n - 1, maxKVal));
            int[][] res = kmeans(xn, bestK, policy.clusterMaxIter, policy.clusterNInit, ff.size());
            bestLabels = res;
            bestCentroids = computeCentroids(xn, res[0], bestK, ff.size());
            bestSil = silhouette(xn, res[0], bestK, ff.size());
        } else {
            bestK = 2; bestSil = -1; bestLabels = null; bestCentroids = null;
            for (int kk = 2; kk < Math.min(maxKVal + 1, n); kk++) {
                int[][] res = kmeans(xn, kk, policy.clusterMaxIter, policy.clusterNInit, ff.size());
                double sil = silhouette(xn, res[0], kk, ff.size());
                if (sil > bestSil) {
                    bestSil = sil; bestK = kk; bestLabels = res;
                    bestCentroids = computeCentroids(xn, res[0], kk, ff.size());
                }
            }
        }

        List<Map<String, Object>> clusters = new ArrayList<>();
        for (int c = 0; c < bestK; c++) {
            int count = 0;
            for (int[] l : bestLabels) if (l[0] == c) count++;
            // not right - labels is int[n][1]
        }
        // Rebuild from labels properly
        int[] labels = bestLabels[0];
        clusters.clear();
        for (int c = 0; c < bestK; c++) {
            int count = 0;
            for (int l : labels) if (l == c) count++;
            if (count == 0) continue;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("cluster", c); info.put("size", count);
            for (int j = 0; j < ff.size(); j++) {
                double centOrig = bestCentroids[c][j] * std[j] + mu[j];
                info.put(ff.get(j) + "_centroid", r4(centOrig));
            }
            clusters.add(info);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fields", ff); r.put("samples", n); r.put("k", bestK);
        r.put("silhouette_score", r4(bestSil)); r.put("clusters", clusters);
        return r;
    }

    // ── K-means internals ──────────────────────────────────────────────────

    private static int[][] kmeans(List<double[]> xn, int k, int maxIter, int nInit, int p) {
        int n = xn.size();
        int[] bestLabels = null; double bestInertia = Double.MAX_VALUE;
        Random rng = new Random(42);
        for (int init = 0; init < nInit; init++) {
            Set<Integer> chosen = new LinkedHashSet<>();
            while (chosen.size() < k) chosen.add(rng.nextInt(n));
            double[][] centroids = new double[k][p];
            int ci = 0;
            for (int idx : chosen) { System.arraycopy(xn.get(idx), 0, centroids[ci], 0, p); ci++; }
            int[] labels = new int[n];
            for (int iter = 0; iter < maxIter; iter++) {
                for (int i = 0; i < n; i++) {
                    double minD = Double.MAX_VALUE; int minC = 0;
                    for (int c = 0; c < k; c++) {
                        double d = dist2(xn.get(i), centroids[c], p);
                        if (d < minD) { minD = d; minC = c; }
                    }
                    labels[i] = minC;
                }
                double[][] newC = new double[k][p]; int[] counts = new int[k];
                for (int i = 0; i < n; i++) {
                    counts[labels[i]]++;
                    for (int j = 0; j < p; j++) newC[labels[i]][j] += xn.get(i)[j];
                }
                boolean converged = true;
                for (int c = 0; c < k; c++) {
                    if (counts[c] > 0) for (int j = 0; j < p; j++) newC[c][j] /= counts[c];
                    else System.arraycopy(centroids[c], 0, newC[c], 0, p);
                    if (converged && dist2(newC[c], centroids[c], p) > 1e-12) converged = false;
                }
                centroids = newC;
                if (converged) break;
            }
            double inertia = 0;
            for (int i = 0; i < n; i++) inertia += dist2(xn.get(i), centroids[labels[i]], p);
            if (inertia < bestInertia) { bestInertia = inertia; bestLabels = labels.clone(); }
        }
        return new int[][]{bestLabels};
    }

    private static double[][] computeCentroids(List<double[]> xn, int[] labels, int k, int p) {
        double[][] c = new double[k][p]; int[] cnt = new int[k];
        for (int i = 0; i < labels.length; i++) {
            cnt[labels[i]]++;
            for (int j = 0; j < p; j++) c[labels[i]][j] += xn.get(i)[j];
        }
        for (int ci = 0; ci < k; ci++) if (cnt[ci] > 0) for (int j = 0; j < p; j++) c[ci][j] /= cnt[ci];
        return c;
    }

    private static double silhouette(List<double[]> xn, int[] labels, int k, int p) {
        int n = xn.size();
        if (k < 2) return 0;
        int check = Math.min(n, 500); double sum = 0;
        for (int i = 0; i < check; i++) {
            int ci = labels[i];
            double a = 0; int ca = 0;
            for (int j = 0; j < n; j++) if (labels[j] == ci && j != i) { a += Math.sqrt(dist2(xn.get(i), xn.get(j), p)); ca++; }
            a = ca > 0 ? a / ca : 0;
            double b = Double.MAX_VALUE;
            for (int c = 0; c < k; c++) {
                if (c == ci) continue;
                double d = 0; int cnt = 0;
                for (int j = 0; j < n; j++) if (labels[j] == c) { d += Math.sqrt(dist2(xn.get(i), xn.get(j), p)); cnt++; }
                if (cnt > 0) b = Math.min(b, d / cnt);
            }
            double m = Math.max(a, b);
            sum += m > 1e-12 ? (b - a) / m : 0;
        }
        return sum / check;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static double dist2(double[] a, double[] b, int p) {
        double s = 0; for (int j = 0; j < p; j++) { double d = a[j] - b[j]; s += d * d; } return s;
    }

    private static double[] colMean(List<double[]> mat, int p, int n) {
        double[] m = new double[p];
        for (double[] row : mat) for (int j = 0; j < p; j++) m[j] += row[j];
        for (int j = 0; j < p; j++) m[j] /= n;
        return m;
    }

    private static double[] colStd(List<double[]> mat, double[] mu, int p, int n) {
        double[] s = new double[p];
        for (double[] row : mat) for (int j = 0; j < p; j++) { double d = row[j] - mu[j]; s[j] += d * d; }
        for (int j = 0; j < p; j++) { s[j] = Math.sqrt(s[j] / n); if (s[j] < 1e-12) s[j] = 1.0; }
        return s;
    }

    private static List<double[]> normalize(List<double[]> mat, double[] mu, double[] std, int p) {
        List<double[]> out = new ArrayList<>();
        for (double[] row : mat) { double[] r = new double[p]; for (int j = 0; j < p; j++) r[j] = (row[j] - mu[j]) / std[j]; out.add(r); }
        return out;
    }

    private static List<String> numericCols(Map<String, String> ct) {
        List<String> r = new ArrayList<>(); for (var e : ct.entrySet()) if ("numeric".equals(e.getValue())) r.add(e.getKey()); return r;
    }

    private static List<String> filterFields(List<String> f, List<String> allowed) {
        Set<String> s = new HashSet<>(allowed); List<String> r = new ArrayList<>(); for (String x : f) if (s.contains(x)) r.add(x); return r;
    }

    private static double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
