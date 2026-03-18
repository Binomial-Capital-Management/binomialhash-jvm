package com.binomialtechnologies.binomialhash.manifold;

import java.util.*;

/**
 * Diagnostic computations over the manifold grid: boundary wrap detection,
 * curvature, Forman-Ricci, Morse classification, persistence, Betti numbers,
 * and interaction curvature.
 */
public final class ManifoldDiagnostics {

    private ManifoldDiagnostics() {}

    /** Check if an axis wraps using boundary-wrap diagnostics. Returns [wraps, orientation]. */
    public static int[] checkBoundaryWrap(
            Map<List<String>, GridPoint> grid, List<ManifoldAxis> axes, List<String> fields, int axisIndex) {
        ManifoldAxis axis = axes.get(axisIndex);
        if (!axis.isOrdered() || axis.getSize() < 4) return new int[]{0, 0};

        List<String> valList = new ArrayList<>();
        for (Object v : axis.getValues()) valList.add(String.valueOf(v));
        int sz = axis.getSize();
        int fifth = Math.max(1, sz / 5);
        Set<String> lowValues = new HashSet<>(valList.subList(0, fifth));
        Set<String> highValues = new HashSet<>(valList.subList(sz - fifth, sz));

        List<Map<String, Double>> lowPoints = new ArrayList<>();
        List<Map<String, Double>> highPoints = new ArrayList<>();
        for (Map.Entry<List<String>, GridPoint> e : grid.entrySet()) {
            String axisVal = e.getKey().get(axisIndex);
            if (lowValues.contains(axisVal)) lowPoints.add(e.getValue().getFieldValues());
            else if (highValues.contains(axisVal)) highPoints.add(e.getValue().getFieldValues());
        }
        if (lowPoints.size() < 3 || highPoints.size() < 3) return new int[]{0, 0};

        Map<String, Double> lowMean = meanVector(lowPoints, fields);
        Map<String, Double> highMean = meanVector(highPoints, fields);

        List<Double> similarities = new ArrayList<>();
        int signMatches = 0, signFlips = 0;
        for (String f : fields) {
            double lv = lowMean.getOrDefault(f, 0.0), hv = highMean.getOrDefault(f, 0.0);
            double magSum = Math.abs(lv) + Math.abs(hv);
            if (magSum < 1e-9) continue;
            similarities.add(1.0 - Math.min(Math.abs(Math.abs(lv) - Math.abs(hv)) / magSum, 1.0));
            if (Math.abs(lv) > 1e-9 && Math.abs(hv) > 1e-9) {
                if ((lv > 0) == (hv > 0)) signMatches++;
                else signFlips++;
            }
        }
        if (similarities.isEmpty()) return new int[]{0, 0};
        double avgSim = similarities.stream().mapToDouble(Double::doubleValue).sum() / similarities.size();
        boolean wraps = avgSim >= 0.6;
        int orientation = !wraps ? 0 : (signFlips > signMatches ? -1 : 1);
        return new int[]{wraps ? 1 : 0, orientation};
    }

    /** Approximate curvature at each point from field variation. */
    public static void computeFieldCurvature(Map<List<String>, GridPoint> grid, List<String> fields) {
        Map<Integer, GridPoint> idx = idxMap(grid);
        for (GridPoint pt : grid.values()) {
            if (pt.getNeighbors().isEmpty() || pt.getFieldValues().isEmpty()) continue;
            double total = 0; int count = 0;
            for (String f : fields) {
                Double myVal = pt.getFieldValues().get(f);
                if (myVal == null) continue;
                List<Double> nv = new ArrayList<>();
                for (int ni : pt.getNeighbors()) {
                    GridPoint np = idx.get(ni);
                    if (np != null && np.getFieldValues().containsKey(f)) nv.add(np.getFieldValues().get(f));
                }
                if (nv.isEmpty()) continue;
                double nMean = nv.stream().mapToDouble(Double::doubleValue).sum() / nv.size();
                total += Math.abs(myVal - nMean) / Math.max(Math.abs(nMean), 1.0);
                count++;
            }
            pt.setCurvature(count > 0 ? total / count : 0.0);
        }
    }

    /** Compute Forman-Ricci curvature at each vertex. */
    public static void computeFormanRicci(Map<List<String>, GridPoint> grid) {
        Map<Integer, GridPoint> idx = idxMap(grid);
        for (GridPoint pt : grid.values()) {
            if (pt.getNeighbors().isEmpty()) { pt.setFormanRicci(0.0); continue; }
            int deg = pt.getNeighbors().size();
            double sum = 0; int cnt = 0;
            for (int ni : pt.getNeighbors()) {
                GridPoint np = idx.get(ni);
                if (np != null) { sum += 4.0 - deg - np.getNeighbors().size(); cnt++; }
            }
            pt.setFormanRicci(cnt > 0 ? sum / cnt : 0.0);
        }
    }

    /** Classify each grid point as minimum, saddle, maximum, or regular per field. */
    public static void classifyMorsePoints(Map<List<String>, GridPoint> grid, List<String> fields) {
        Map<Integer, GridPoint> idx = idxMap(grid);
        for (GridPoint pt : grid.values()) {
            if (pt.getNeighbors().isEmpty()) continue;
            for (String f : fields) {
                Double myVal = pt.getFieldValues().get(f);
                if (myVal == null) continue;
                int above = 0, below = 0;
                for (int ni : pt.getNeighbors()) {
                    GridPoint np = idx.get(ni);
                    if (np == null) continue;
                    Double nv = np.getFieldValues().get(f);
                    if (nv == null) continue;
                    if (nv > myVal + 1e-12) above++;
                    else if (nv < myVal - 1e-12) below++;
                }
                if (above + below == 0) continue;
                if (below == 0) pt.getMorseType().put(f, "minimum");
                else if (above == 0) pt.getMorseType().put(f, "maximum");
                else if (above >= 2 && below >= 2) pt.getMorseType().put(f, "saddle");
                else pt.getMorseType().put(f, "regular");
            }
        }
    }

    /** Compute 0-dimensional persistence via sublevel-set filtration. */
    public static List<CriticalPoint> computePersistence(Map<List<String>, GridPoint> grid, String targetField) {
        List<double[]> vertices = new ArrayList<>();
        Map<Integer, GridPoint> idx = idxMap(grid);
        Map<Integer, List<String>> idxToCoord = new HashMap<>();
        for (GridPoint pt : grid.values()) {
            Double val = pt.getFieldValues().get(targetField);
            if (val != null) {
                vertices.add(new double[]{val, pt.getIndex()});
                idxToCoord.put(pt.getIndex(), pt.getAxisCoords());
            }
        }
        vertices.sort(Comparator.comparingDouble(a -> a[0]));
        if (vertices.size() < 2) return Collections.emptyList();

        Map<Integer, Integer> parent = new HashMap<>(), rank = new HashMap<>();
        Map<Integer, Double> compBirth = new HashMap<>();
        Map<Integer, List<String>> compBirthCoord = new HashMap<>();

        List<CriticalPoint> pairs = new ArrayList<>();
        for (double[] v : vertices) {
            int index = (int) v[1]; double value = v[0];
            parent.put(index, index); rank.put(index, 0);
            compBirth.put(index, value); compBirthCoord.put(index, idxToCoord.get(index));

            GridPoint pt = idx.get(index);
            if (pt == null) continue;
            for (int ni : pt.getNeighbors()) {
                if (!parent.containsKey(ni)) continue;
                int ri = find(parent, index), rn = find(parent, ni);
                if (ri == rn) continue;
                int elder, younger;
                if (compBirth.get(ri) <= compBirth.get(rn)) { elder = ri; younger = rn; }
                else { elder = rn; younger = ri; }
                double persistence = value - compBirth.get(younger);
                if (persistence > 1e-12) {
                    pairs.add(new CriticalPoint((int) younger, compBirthCoord.get(younger),
                            "minimum", targetField, round6(compBirth.get(younger)), round6(persistence)));
                }
                if (rank.get(elder) < rank.get(younger)) {
                    parent.put(elder, younger);
                    compBirth.put(younger, compBirth.get(elder));
                    compBirthCoord.put(younger, compBirthCoord.get(elder));
                } else {
                    parent.put(younger, elder);
                    if (rank.get(elder).equals(rank.get(younger))) rank.merge(elder, 1, Integer::sum);
                }
            }
        }
        pairs.sort((a, b) -> Double.compare(b.getPersistence(), a.getPersistence()));
        return pairs;
    }

    /** Compute β₀ and β₁ for the grid graph. Returns [betti0, betti1]. */
    public static int[] computeBettiNumbers(Map<List<String>, GridPoint> grid) {
        if (grid.isEmpty()) return new int[]{0, 0};
        Map<Integer, GridPoint> idx = idxMap(grid);
        Set<Integer> visited = new HashSet<>();
        int components = 0;
        for (GridPoint pt : grid.values()) {
            if (visited.contains(pt.getIndex())) continue;
            components++;
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(pt.getIndex()); visited.add(pt.getIndex());
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                GridPoint cp = idx.get(cur);
                if (cp == null) continue;
                for (int ni : cp.getNeighbors()) {
                    if (visited.add(ni) && idx.containsKey(ni)) queue.add(ni);
                }
            }
        }
        int V = grid.size();
        int E = 0;
        for (GridPoint pt : grid.values()) E += pt.getNeighbors().size();
        E /= 2;
        return new int[]{components, Math.max(0, E - V + components)};
    }

    /** Measure gradient correlation between field pairs. */
    public static List<Map<String, Object>> computeInteractionCurvature(
            Map<List<String>, GridPoint> grid, List<String> fields) {
        if (fields.size() < 2) return Collections.emptyList();
        Map<Integer, GridPoint> idx = idxMap(grid);
        List<String> check = fields.subList(0, Math.min(10, fields.size()));
        List<Map<String, Object>> interactions = new ArrayList<>();
        for (int i = 0; i < check.size(); i++) {
            for (int j = i + 1; j < check.size(); j++) {
                String f1 = check.get(i), f2 = check.get(j);
                List<Double> gradProds = new ArrayList<>();
                for (GridPoint pt : grid.values()) {
                    Double v1 = pt.getFieldValues().get(f1), v2 = pt.getFieldValues().get(f2);
                    if (v1 == null || v2 == null) continue;
                    for (int ni : pt.getNeighbors()) {
                        GridPoint np = idx.get(ni);
                        if (np == null) continue;
                        Double n1 = np.getFieldValues().get(f1), n2 = np.getFieldValues().get(f2);
                        if (n1 == null || n2 == null) continue;
                        gradProds.add((n1 - v1) * (n2 - v2));
                    }
                }
                if (gradProds.size() < 10) continue;
                double mean = gradProds.stream().mapToDouble(Double::doubleValue).sum() / gradProds.size();
                double scale = gradProds.stream().mapToDouble(d -> Math.abs(d)).sum() / gradProds.size();
                double corr = scale > 1e-12 ? mean / scale : 0.0;
                if (Math.abs(corr) < 0.05) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fields", List.of(f1, f2));
                m.put("gradient_correlation", round4(corr));
                m.put("strength", round4(Math.abs(corr)));
                m.put("direction", corr > 0.1 ? "synergistic" : corr < -0.1 ? "antagonistic" : "weak");
                m.put("samples", gradProds.size());
                interactions.add(m);
            }
        }
        interactions.sort((a, b) -> Double.compare((double) b.get("strength"), (double) a.get("strength")));
        return interactions.subList(0, Math.min(15, interactions.size()));
    }

    private static Map<Integer, GridPoint> idxMap(Map<List<String>, GridPoint> grid) {
        Map<Integer, GridPoint> m = new HashMap<>();
        for (GridPoint p : grid.values()) m.put(p.getIndex(), p);
        return m;
    }

    private static int find(Map<Integer, Integer> parent, int i) {
        int root = i;
        while (parent.get(root) != root) root = parent.get(root);
        while (parent.get(i) != root) { int next = parent.get(i); parent.put(i, root); i = next; }
        return root;
    }

    private static double round6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
    private static double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }

    private static Map<String, Double> meanVector(List<Map<String, Double>> points, List<String> fields) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String f : fields) {
            double sum = 0; int cnt = 0;
            for (Map<String, Double> p : points) { Double v = p.get(f); if (v != null) { sum += v; cnt++; } }
            result.put(f, cnt > 0 ? sum / cnt : 0.0);
        }
        return result;
    }
}
