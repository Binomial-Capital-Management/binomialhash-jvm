package com.binomialtechnologies.binomialhash.insights;

import com.binomialtechnologies.binomialhash.schema.ColumnType;
import com.binomialtechnologies.binomialhash.stats.StatsHelpers;

import java.util.*;

/**
 * Objective-driven insight extraction over tabular data.
 *
 * <p>Provides: driver discovery, residual surprises, regime boundary detection,
 * branch divergence scoring, and counterfactual direction guidance.</p>
 *
 * <p>All methods operate on raw rows — they do not depend on the manifold grid.</p>
 */
public final class InsightEngine {

    private InsightEngine() {}

    /**
     * Fit univariate linear models and return the best driver by R².
     */
    public static Map<String, Object> discoverBestDriver(
            List<Map<String, Object>> rows, String target, List<String> drivers, int minSamples) {
        List<double[]> targetPairs = StatsHelpers.numericColumnValues(rows, target);
        if (targetPairs.size() < minSamples) return null;

        Map<Integer, Double> targetByIdx = new HashMap<>();
        for (double[] pair : targetPairs) {
            targetByIdx.put((int) pair[0], pair[1]);
        }

        Map<String, Object> best = null;
        double bestR2 = -1;

        for (String driver : drivers) {
            List<double[]> pairs = StatsHelpers.numericColumnValues(rows, driver);
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            for (double[] pair : pairs) {
                Double y = targetByIdx.get((int) pair[0]);
                if (y != null) {
                    xs.add(pair[1]);
                    ys.add(y);
                }
            }
            if (xs.size() < minSamples) continue;

            double[] fit = StatsHelpers.fitLinear(xs, ys);
            double slope = fit[0], intercept = fit[1], r2 = fit[2];
            double corr = StatsHelpers.pearsonCorr(xs, ys);

            if (r2 > bestR2) {
                bestR2 = r2;
                best = new LinkedHashMap<>();
                best.put("driver", driver);
                best.put("slope", slope);
                best.put("intercept", intercept);
                best.put("r2", r2);
                best.put("corr", corr);
                best.put("samples", xs.size());
            }
        }
        return best;
    }

    /**
     * Return the top-k strongest residual contradictions to a fitted law.
     */
    public static List<Map<String, Object>> computeSurprises(
            List<Map<String, Object>> rows, String target, String driver,
            double slope, double intercept, int topK) {

        List<double[]> temp = new ArrayList<>();
        List<Double> residuals = new ArrayList<>();

        for (int idx = 0; idx < rows.size(); idx++) {
            Double x = StatsHelpers.toFloatPermissive(rows.get(idx).get(driver));
            Double y = StatsHelpers.toFloatPermissive(rows.get(idx).get(target));
            if (x == null || y == null) continue;
            double expected = slope * x + intercept;
            double resid = y - expected;
            residuals.add(resid);
            temp.add(new double[]{idx, y, expected, resid});
        }

        double residStd = pstdev(residuals);
        temp.sort((a, b) -> Double.compare(Math.abs(b[3]), Math.abs(a[3])));

        List<Map<String, Object>> surprises = new ArrayList<>();
        int limit = Math.max(topK, 1);
        for (int i = 0; i < Math.min(limit, temp.size()); i++) {
            double[] t = temp.get(i);
            double z = residStd > 1e-9 ? t[3] / residStd : 0.0;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("row_index", (int) t[0]);
            s.put("actual", round6(t[1]));
            s.put("expected", round6(t[2]));
            s.put("residual", round6(t[3]));
            s.put("residual_z", round4(z));
            surprises.add(s);
        }
        return surprises;
    }

    /**
     * Detect abrupt jumps in target means across driver quantile buckets.
     */
    public static List<Map<String, Object>> computeRegimeBoundaries(
            List<Map<String, Object>> rows, String target, String driver,
            int bins, double zThreshold) {

        List<double[]> driverPairs = StatsHelpers.numericColumnValues(rows, driver);
        List<Double> xValues = new ArrayList<>();
        for (double[] p : driverPairs) xValues.add(p[1]);

        double[] edges = StatsHelpers.quantileEdges(xValues, bins);
        int nBuckets = Math.max(edges.length - 1, 1);
        double[] bucketSums = new double[nBuckets];
        int[] bucketCounts = new int[nBuckets];

        for (Map<String, Object> row : rows) {
            Double x = StatsHelpers.toFloatPermissive(row.get(driver));
            Double y = StatsHelpers.toFloatPermissive(row.get(target));
            if (x == null || y == null) continue;
            int b = StatsHelpers.bucketIndex(x, edges);
            bucketSums[b] += y;
            bucketCounts[b]++;
        }

        double[] means = new double[nBuckets];
        for (int i = 0; i < nBuckets; i++) {
            means[i] = bucketCounts[i] > 0 ? bucketSums[i] / bucketCounts[i] : 0.0;
        }

        double[] deltas = new double[Math.max(means.length - 1, 0)];
        List<Double> deltaList = new ArrayList<>();
        for (int i = 0; i < deltas.length; i++) {
            deltas[i] = means[i + 1] - means[i];
            deltaList.add(deltas[i]);
        }
        double deltaStd = pstdev(deltaList);

        List<Map<String, Object>> boundaries = new ArrayList<>();
        for (int i = 0; i < deltas.length; i++) {
            double z = deltaStd > 1e-9 ? deltas[i] / deltaStd : 0.0;
            if (Math.abs(z) >= zThreshold) {
                double hi = (i + 2 < edges.length) ? edges[i + 2] : edges[edges.length - 1];
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("between_bucket", List.of(i, i + 1));
                b.put("driver_range", List.of(round6(edges[i]), round6(hi)));
                b.put("target_jump", round6(deltas[i]));
                b.put("jump_z", round4(z));
                boundaries.add(b);
            }
        }
        return boundaries;
    }

    /**
     * Score how much latent context spreads within each target quantile bucket.
     */
    public static List<Map<String, Object>> computeBranchDivergence(
            List<Map<String, Object>> rows, String target, List<String> numericCols,
            int targetBins, int contextLimit, int minRows, int minValues) {

        List<double[]> targetPairs = StatsHelpers.numericColumnValues(rows, target);
        List<Double> tValues = new ArrayList<>();
        for (double[] p : targetPairs) tValues.add(p[1]);
        double[] tEdges = StatsHelpers.quantileEdges(tValues, targetBins);

        List<String> contextCols = new ArrayList<>();
        for (String c : numericCols) {
            if (!c.equals(target) && contextCols.size() < contextLimit) {
                contextCols.add(c);
            }
        }

        List<Map<String, Object>> divergences = new ArrayList<>();
        int nBuckets = Math.max(tEdges.length - 1, 1);

        for (int b = 0; b < nBuckets; b++) {
            List<Map<String, Object>> members = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Double y = StatsHelpers.toFloatPermissive(row.get(target));
                if (y == null) continue;
                if (StatsHelpers.bucketIndex(y, tEdges) == b) {
                    members.add(row);
                }
            }
            if (members.size() < minRows) continue;

            List<Double> spreadScores = new ArrayList<>();
            for (String c : contextCols) {
                List<Double> vals = new ArrayList<>();
                for (Map<String, Object> r : members) {
                    Double v = StatsHelpers.toFloatPermissive(r.get(c));
                    if (v != null) vals.add(v);
                }
                if (vals.size() < minValues) continue;
                double vmin = Collections.min(vals);
                double vmax = Collections.max(vals);
                double meanAbs = Math.abs(vals.stream().mapToDouble(Double::doubleValue).sum() / vals.size());
                if (meanAbs < 1e-12) meanAbs = 1.0;
                spreadScores.add((vmax - vmin) / meanAbs);
            }

            if (!spreadScores.isEmpty()) {
                double hi = (b + 1 < tEdges.length) ? tEdges[b + 1] : tEdges[tEdges.length - 1];
                double avgSpread = spreadScores.stream().mapToDouble(Double::doubleValue).sum() / spreadScores.size();
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("target_quantile_bucket", b);
                d.put("target_range", List.of(round6(tEdges[b]), round6(hi)));
                d.put("rows", members.size());
                d.put("latent_spread_score", round6(avgSpread));
                divergences.add(d);
            }
        }
        divergences.sort((a, b) -> Double.compare(
                (double) b.get("latent_spread_score"),
                (double) a.get("latent_spread_score")));
        return divergences;
    }

    /**
     * Build a counterfactual suggestion from a fitted linear law.
     */
    public static Map<String, Object> buildCounterfactual(
            List<Map<String, Object>> rows, String target, String driver,
            Map<String, Object> model, Map<String, Object> objective) {

        double slope = ((Number) model.get("slope")).doubleValue();
        double intercept = ((Number) model.get("intercept")).doubleValue();
        String goal = String.valueOf(objective.getOrDefault("goal", "maximize")).toLowerCase();

        Map<String, Object> cf = new LinkedHashMap<>();
        cf.put("driver", driver);
        cf.put("target", target);

        Map<String, Object> law = new LinkedHashMap<>();
        law.put("formula", String.format("%s ≈ %.6f * %s + %.6f", target, slope, driver, intercept));
        law.put("r2", round4(((Number) model.get("r2")).doubleValue()));
        law.put("corr", round4(((Number) model.get("corr")).doubleValue()));
        cf.put("law", law);

        String direction;
        if ("minimize".equals(goal)) {
            direction = slope < 0 ? "increase" : "decrease";
        } else {
            direction = slope > 0 ? "increase" : "decrease";
        }

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("goal", goal);
        action.put("driver_direction", direction);
        action.put("rationale", "Direction chosen from fitted slope sign under stated objective.");
        cf.put("suggested_action", action);

        Double targetValue = StatsHelpers.toFloatPermissive(objective.get("target_value"));
        List<double[]> targetPairs = StatsHelpers.numericColumnValues(rows, target);
        if (!targetPairs.isEmpty() && targetValue != null && Math.abs(slope) > 1e-9) {
            double currentMean = targetPairs.stream().mapToDouble(p -> p[1]).sum() / targetPairs.size();
            double requiredDriver = (targetValue - intercept) / slope;
            List<double[]> driverPairs = StatsHelpers.numericColumnValues(rows, driver);
            double driverMean = driverPairs.isEmpty() ? 0.0
                    : driverPairs.stream().mapToDouble(p -> p[1]).sum() / driverPairs.size();

            Map<String, Object> estimate = new LinkedHashMap<>();
            estimate.put("current_target_mean", round6(currentMean));
            estimate.put("desired_target_value", round6(targetValue));
            estimate.put("required_driver_value", round6(requiredDriver));
            estimate.put("delta_driver_from_mean", round6(requiredDriver - driverMean));
            cf.put("reach_value_estimate", estimate);
        }
        return cf;
    }

    /**
     * Full objective-driven insight pipeline over tabular rows.
     */
    public static Map<String, Object> computeInsights(
            List<Map<String, Object>> rows, List<String> columns, Map<String, String> colTypes,
            Map<String, Object> objective, int topK, int driverLimit, int driverBins,
            double regimeZThreshold, int targetBins, int branchContextLimit,
            int branchMinRows, int branchMinValues) {

        List<String> numericCols = new ArrayList<>();
        for (String c : columns) {
            if (ColumnType.NUMERIC.value().equals(colTypes.get(c))) {
                numericCols.add(c);
            }
        }
        if (numericCols.isEmpty()) {
            return Map.of("error", "No numeric columns available for insights.");
        }

        String target = (String) objective.get("target");
        if (target == null || !numericCols.contains(target)) {
            target = numericCols.get(0);
        }

        List<double[]> targetPairs = StatsHelpers.numericColumnValues(rows, target);
        if (targetPairs.size() < 30) {
            return Map.of("error", "Insufficient numeric data for target '" + target + "' (need >=30 rows).");
        }

        List<String> drivers = new ArrayList<>();
        for (String c : numericCols) {
            if (!c.equals(target) && drivers.size() < driverLimit) drivers.add(c);
        }

        Map<String, Object> best = discoverBestDriver(rows, target, drivers, 30);
        if (best == null) {
            return Map.of("error", "Could not fit a law for target '" + target + "'.");
        }

        String driver = (String) best.get("driver");
        String goal = String.valueOf(objective.getOrDefault("goal", "maximize")).toLowerCase();

        var surprises = computeSurprises(rows, target, driver,
                ((Number) best.get("slope")).doubleValue(),
                ((Number) best.get("intercept")).doubleValue(), topK);
        var boundaries = computeRegimeBoundaries(rows, target, driver, driverBins, regimeZThreshold);
        var divergence = computeBranchDivergence(rows, target, numericCols,
                targetBins, branchContextLimit, branchMinRows, branchMinValues);
        var counterfactual = buildCounterfactual(rows, target, driver, best, objective);

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("target", target);
        obj.put("goal", goal);
        obj.put("target_value", objective.get("target_value"));
        result.put("objective", obj);

        Map<String, Object> discoveredLaw = new LinkedHashMap<>();
        discoveredLaw.put("driver", driver);
        discoveredLaw.put("target", target);
        discoveredLaw.put("slope", round6(((Number) best.get("slope")).doubleValue()));
        discoveredLaw.put("intercept", round6(((Number) best.get("intercept")).doubleValue()));
        discoveredLaw.put("r2", round4(((Number) best.get("r2")).doubleValue()));
        discoveredLaw.put("corr", round4(((Number) best.get("corr")).doubleValue()));
        discoveredLaw.put("samples", best.get("samples"));
        result.put("discovered_law", discoveredLaw);

        int limit = Math.max(topK, 1);
        Map<String, Object> insights = new LinkedHashMap<>();
        insights.put("surprises", surprises);
        insights.put("regime_boundaries", boundaries.subList(0, Math.min(limit, boundaries.size())));
        insights.put("branch_divergence", divergence.subList(0, Math.min(limit, divergence.size())));
        insights.put("counterfactual", counterfactual);
        result.put("insights", insights);

        result.put("method_notes", List.of(
                "Insights are objective-driven and deterministic from fitted structural laws.",
                "No hardcoded shape labels are used; transitions emerge from data quantiles and jumps.",
                "Branch divergence flags non-equivalent contexts at similar headline target levels."
        ));
        return result;
    }

    private static double pstdev(List<Double> values) {
        if (values.size() <= 1) return 0.0;
        double mean = values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
        double sumSq = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
        return Math.sqrt(sumSq / values.size());
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
