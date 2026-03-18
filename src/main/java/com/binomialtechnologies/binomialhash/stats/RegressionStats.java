package com.binomialtechnologies.binomialhash.stats;

import java.util.*;

import static com.binomialtechnologies.binomialhash.stats.StatsHelpers.*;

/**
 * Regression, partial correlation, and OLS-based analysis.
 */
public final class RegressionStats {

    private RegressionStats() {}

    /** Multivariate OLS regression. */
    public static Map<String, Object> regressDataset(
            List<Map<String, Object>> rows, List<String> columns, Map<String, String> colTypes,
            String target, List<String> drivers, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        if (drivers == null || drivers.isEmpty()) return Map.of("error", "drivers must be non-empty.");
        List<String> allCols = new ArrayList<>(); allCols.add(target); allCols.addAll(drivers);
        for (String c : allCols)
            if (!colTypes.containsKey(c)) return Map.of("error", "Column '" + c + "' not found.");

        List<double[]> xsAll = new ArrayList<>(); List<Double> ys = new ArrayList<>();
        for (var row : rows) {
            Double yv = toFloatPermissive(row.get(target)); if (yv == null) continue;
            double[] xrow = new double[drivers.size()]; boolean skip = false;
            for (int j = 0; j < drivers.size(); j++) {
                Double xv = toFloatPermissive(row.get(drivers.get(j)));
                if (xv == null) { skip = true; break; }
                xrow[j] = xv;
            }
            if (!skip) { xsAll.add(xrow); ys.add(yv); }
        }
        int n = ys.size(); int p = drivers.size();
        if (n < p + policy.regressionMinExtraSamples) return Map.of("error", "Not enough rows (" + n + ").");

        double[] yArr = ys.stream().mapToDouble(Double::doubleValue).toArray();
        double r2 = olsR2(xsAll, yArr);
        double adjR2 = n > p + 1 ? 1.0 - ((1 - r2) * (n - 1.0) / (n - p - 1)) : r2;

        // Full OLS for coefficients
        double[] beta = olsBeta(xsAll, yArr, p);
        if (beta == null) return Map.of("error", "Singular matrix — drivers may be collinear.");

        List<Map<String, Object>> driverResults = new ArrayList<>();
        for (int j = 0; j < p; j++) {
            double[] xCol = new double[n]; for (int i = 0; i < n; i++) xCol[i] = xsAll.get(i)[j];
            double corr = pearsonCorr(xCol, yArr);
            driverResults.add(Map.of("driver", drivers.get(j), "coefficient", r6(beta[j + 1]), "individual_correlation", r4(corr)));
        }
        driverResults.sort((a, b) -> Double.compare(Math.abs((double) b.get("coefficient")), Math.abs((double) a.get("coefficient"))));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("target", target); res.put("samples", n); res.put("intercept", r6(beta[0]));
        res.put("r2", r4(r2)); res.put("adjusted_r2", r4(adjR2)); res.put("drivers", driverResults);
        return res;
    }

    /** Partial correlation of A and B after removing control effects. */
    public static Map<String, Object> partialCorrelateDataset(
            List<Map<String, Object>> rows, Map<String, String> colTypes,
            String fieldA, String fieldB, List<String> controls, StatsPolicy policy) {
        if (policy == null) policy = StatsPolicy.DEFAULT;
        if (controls == null) controls = List.of();
        List<String> allCheck = new ArrayList<>();
        allCheck.add(fieldA); allCheck.add(fieldB); allCheck.addAll(controls);
        for (String c : allCheck)
            if (!colTypes.containsKey(c)) return Map.of("error", "Column '" + c + "' not found.");

        List<Double> aVals = new ArrayList<>(), bVals = new ArrayList<>();
        List<double[]> ctrlVals = new ArrayList<>();
        for (var row : rows) {
            Double av = toFloatPermissive(row.get(fieldA)), bv = toFloatPermissive(row.get(fieldB));
            if (av == null || bv == null) continue;
            double[] cvs = new double[controls.size()]; boolean skip = false;
            for (int j = 0; j < controls.size(); j++) {
                Double cv = toFloatPermissive(row.get(controls.get(j)));
                if (cv == null) { skip = true; break; }
                cvs[j] = cv;
            }
            if (!skip) { aVals.add(av); bVals.add(bv); ctrlVals.add(cvs); }
        }
        int n = aVals.size();
        if (n < controls.size() + policy.partialCorrMinExtraSamples)
            return Map.of("error", "Not enough rows (" + n + ").");

        double[] aArr = aVals.stream().mapToDouble(Double::doubleValue).toArray();
        double[] bArr = bVals.stream().mapToDouble(Double::doubleValue).toArray();
        double raw = pearsonCorr(aArr, bArr);
        if (controls.isEmpty()) {
            return Map.of("field_a", fieldA, "field_b", fieldB, "controls", List.of(),
                    "partial_correlation", r4(raw), "raw_correlation", r4(raw), "samples", n);
        }

        double[] residA = residuals(aArr, ctrlVals, n, controls.size());
        double[] residB = residuals(bArr, ctrlVals, n, controls.size());
        double partial = pearsonCorr(residA, residB);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("field_a", fieldA); res.put("field_b", fieldB); res.put("controls", controls);
        res.put("partial_correlation", r4(partial)); res.put("raw_correlation", r4(raw));
        res.put("samples", n); res.put("change", r4(partial - raw));
        return res;
    }

    // ── internal ───────────────────────────────────────────────────────────

    private static double[] residuals(double[] vals, List<double[]> ctrl, int n, int p) {
        List<double[]> xs = new ArrayList<>();
        for (double[] c : ctrl) xs.add(c);
        double[] beta = olsBeta(xs, vals, p);
        if (beta == null) return vals;
        double[] resid = new double[n];
        for (int i = 0; i < n; i++) {
            double pred = beta[0]; for (int j = 0; j < p; j++) pred += beta[j + 1] * ctrl.get(i)[j];
            resid[i] = vals[i] - pred;
        }
        return resid;
    }

    static double[] olsBeta(List<double[]> xsAll, double[] ys, int p) {
        int n = ys.length;
        double[][] xtx = new double[p + 1][p + 1]; double[] xty = new double[p + 1];
        for (int i = 0; i < n; i++) {
            double[] re = new double[p + 1]; re[0] = 1;
            System.arraycopy(xsAll.get(i), 0, re, 1, p);
            for (int a = 0; a <= p; a++) {
                xty[a] += re[a] * ys[i];
                for (int b = 0; b <= p; b++) xtx[a][b] += re[a] * re[b];
            }
        }
        double[][] aug = new double[p + 1][p + 2];
        for (int r = 0; r <= p; r++) { System.arraycopy(xtx[r], 0, aug[r], 0, p + 1); aug[r][p + 1] = xty[r]; }
        for (int col = 0; col <= p; col++) {
            int mr = col;
            for (int ri = col + 1; ri <= p; ri++) if (Math.abs(aug[ri][col]) > Math.abs(aug[mr][col])) mr = ri;
            double[] tmp = aug[col]; aug[col] = aug[mr]; aug[mr] = tmp;
            if (Math.abs(aug[col][col]) < 1e-12) return null;
            for (int ri = col + 1; ri <= p; ri++) {
                double f = aug[ri][col] / aug[col][col];
                for (int j = col; j <= p + 1; j++) aug[ri][j] -= f * aug[col][j];
            }
        }
        double[] beta = new double[p + 1];
        for (int i = p; i >= 0; i--) {
            beta[i] = aug[i][p + 1];
            for (int j = i + 1; j <= p; j++) beta[i] -= aug[i][j] * beta[j];
            beta[i] /= aug[i][i];
        }
        return beta;
    }

    private static double r6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
    private static double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
