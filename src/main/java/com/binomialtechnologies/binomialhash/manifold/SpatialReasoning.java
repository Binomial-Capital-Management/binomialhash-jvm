package com.binomialtechnologies.binomialhash.manifold;

import java.util.*;

import org.apache.commons.math3.linear.*;

/**
 * Spatial reasoning primitives for manifold geometry.
 *
 * <p>Uses Commons Math for eigenvalue decomposition of the graph Laplacian.
 * Provides heat kernel signatures, Reeb graph, vector field analysis,
 * Laplacian spectrum, scalar harmonics, and diffusion distance.</p>
 */
public final class SpatialReasoning {

    private SpatialReasoning() {}

    private static Map<Integer, GridPoint> idxMap(ManifoldSurface s) {
        Map<Integer, GridPoint> m = new HashMap<>();
        for (GridPoint gp : s.getGrid().values()) m.put(gp.getIndex(), gp);
        return m;
    }

    /** L = D - A (unnormalized graph Laplacian). */
    private static RealMatrix buildGraphLaplacian(ManifoldSurface surface, List<Integer> ordered, Map<Integer, Integer> pos) {
        int n = ordered.size();
        RealMatrix L = new Array2DRowRealMatrix(n, n);
        Map<Integer, GridPoint> idx = idxMap(surface);
        for (int id : ordered) {
            GridPoint gp = idx.get(id);
            int i = pos.get(id); int deg = 0;
            for (int ni : gp.getNeighbors()) {
                Integer j = pos.get(ni);
                if (j != null) { L.setEntry(i, j, -1.0); deg++; }
            }
            L.setEntry(i, i, deg);
        }
        return L;
    }

    private static void prepareOrdered(ManifoldSurface surface, List<Integer> ordered, Map<Integer, Integer> pos) {
        for (GridPoint gp : surface.getGrid().values()) ordered.add(gp.getIndex());
        Collections.sort(ordered);
        for (int i = 0; i < ordered.size(); i++) pos.put(ordered.get(i), i);
    }

    private static EigenDecomposition eigenDecompose(RealMatrix L) {
        return new EigenDecomposition(L);
    }

    /** Sort eigenvalues ascending and return indices. */
    private static int[] sortedEigenIndices(EigenDecomposition ed, int n) {
        double[] evals = ed.getRealEigenvalues();
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        Arrays.sort(indices, Comparator.comparingDouble(i -> evals[i]));
        int[] result = new int[n];
        for (int i = 0; i < n; i++) result[i] = indices[i];
        return result;
    }

    // ── heat kernel ─────────────────────────────────────────────────────────

    public static Map<String, Object> heatKernel(
            ManifoldSurface surface, String targetField, List<Double> timeScales,
            int nEigen, int topKBottlenecks) {
        if (surface.getVertexCount() < 4) return Map.of("error", "Grid too small for heat kernel analysis.");
        List<Integer> ordered = new ArrayList<>(); Map<Integer, Integer> pos = new HashMap<>();
        prepareOrdered(surface, ordered, pos);
        int n = ordered.size();
        int nEig = Math.min(nEigen, n - 1);
        if (nEig < 2) return Map.of("error", "Not enough vertices.");
        RealMatrix L = buildGraphLaplacian(surface, ordered, pos);
        EigenDecomposition ed = eigenDecompose(L);
        int[] sortIdx = sortedEigenIndices(ed, n);

        double[] evals = new double[nEig];
        double[][] evecs = new double[n][nEig];
        for (int k = 0; k < nEig; k++) {
            evals[k] = ed.getRealEigenvalue(sortIdx[k]);
            double[] v = ed.getEigenvector(sortIdx[k]).toArray();
            for (int i = 0; i < n; i++) evecs[i][k] = v[i];
        }

        if (timeScales == null || timeScales.isEmpty()) {
            double lamMax = Math.max(evals[nEig - 1], 1e-6);
            double lamMin = Math.max(nEig > 1 ? evals[1] : 1e-6, 1e-6);
            timeScales = List.of(0.1 / lamMax, 1.0 / lamMax, 1.0 / lamMin, 10.0 / lamMin);
        }
        int T = timeScales.size();
        double[][] hks = new double[n][T];
        for (int ti = 0; ti < T; ti++) {
            double t = timeScales.get(ti);
            for (int i = 0; i < n; i++) {
                double s = 0;
                for (int k = 0; k < nEig; k++) s += Math.exp(-evals[k] * t) * evecs[i][k] * evecs[i][k];
                hks[i][ti] = s;
            }
        }
        Map<Integer, GridPoint> idx = idxMap(surface);
        double[] bScores = new double[n];
        for (int i = 0; i < n; i++) {
            GridPoint gp = idx.get(ordered.get(i));
            double sumN = 0; int cnt = 0;
            for (int ni : gp.getNeighbors()) {
                Integer j = pos.get(ni);
                if (j != null) { sumN += hks[j][0]; cnt++; }
            }
            if (cnt > 0) {
                double meanN = sumN / cnt;
                if (meanN > 1e-12) bScores[i] = Math.max(0.0, 1.0 - hks[i][0] / meanN);
            }
        }
        Integer[] topIdx = new Integer[n];
        for (int i = 0; i < n; i++) topIdx[i] = i;
        Arrays.sort(topIdx, (a, b) -> Double.compare(bScores[b], bScores[a]));
        List<Map<String, Object>> bottlenecks = new ArrayList<>();
        for (int k = 0; k < Math.min(topKBottlenecks, n); k++) {
            int ii = topIdx[k];
            if (bScores[ii] < 1e-6) break;
            GridPoint gp = idx.get(ordered.get(ii));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("coord", gp.getAxisCoords()); e.put("bottleneck_score", r6(bScores[ii])); e.put("density", gp.getDensity());
            if (targetField != null && gp.getFieldValues().containsKey(targetField))
                e.put("target_value", r6(gp.getFieldValues().get(targetField)));
            bottlenecks.add(e);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("vertices", n); r.put("eigenvalues_used", nEig);
        r.put("time_scales", timeScales.stream().map(SpatialReasoning::r6).toList());
        r.put("bottlenecks", bottlenecks);
        r.put("spectral_gap", r6(nEig > 1 ? evals[1] : 0));
        return r;
    }

    // ── reeb graph ──────────────────────────────────────────────────────────

    public static Map<String, Object> reebGraph(ManifoldSurface surface, String targetField, int nLevels) {
        Map<Integer, GridPoint> idx = idxMap(surface);
        List<double[]> valued = new ArrayList<>();
        for (GridPoint gp : surface.getGrid().values()) {
            Double v = gp.getFieldValues().get(targetField);
            if (v != null) valued.add(new double[]{v, gp.getIndex()});
        }
        if (valued.size() < 4) return Map.of("error", "Not enough valued points for Reeb graph.");
        valued.sort(Comparator.comparingDouble(a -> a[0]));
        int vn = valued.size();

        TreeSet<Double> threshSet = new TreeSet<>();
        for (int i = 0; i <= nLevels; i++) {
            int qi = Math.min((int) ((double) i / nLevels * (vn - 1)), vn - 1);
            threshSet.add(valued.get(qi)[0]);
        }
        List<Double> thresholds = new ArrayList<>(threshSet);
        if (thresholds.size() < 2) return Map.of("error", "All values identical.");

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> arcs = new ArrayList<>();
        int nodeId = 0;
        Map<Integer, Integer> prevNodeMap = new HashMap<>();
        List<Set<Integer>> prevComps = new ArrayList<>();

        for (int li = 0; li < thresholds.size() - 1; li++) {
            double lo = thresholds.get(li), hi = thresholds.get(li + 1);
            double levelVal = r6((lo + hi) / 2);
            Set<Integer> active = new HashSet<>();
            for (double[] v : valued) { if (v[0] >= lo && v[0] < hi) active.add((int) v[1]); }
            List<Set<Integer>> curComps = connectedComponents(active, idx);

            if (prevComps.isEmpty()) {
                for (Set<Integer> comp : curComps) {
                    Map<String, Object> nd = new LinkedHashMap<>();
                    nd.put("id", nodeId); nd.put("type", "birth"); nd.put("level", levelVal); nd.put("size", comp.size());
                    nodes.add(nd);
                    for (int gid : comp) prevNodeMap.put(gid, nodeId);
                    nodeId++;
                }
                prevComps = curComps;
                continue;
            }

            Map<Integer, Integer> curNodeIds = new HashMap<>();
            for (Set<Integer> curComp : curComps) {
                Set<Integer> parentNodes = new HashSet<>();
                for (Set<Integer> prevComp : prevComps) {
                    Set<Integer> overlap = new HashSet<>(curComp);
                    overlap.retainAll(prevComp);
                    for (int og : overlap) { Integer pn = prevNodeMap.get(og); if (pn != null) parentNodes.add(pn); }
                }
                if (parentNodes.isEmpty()) {
                    Map<String, Object> nd = new LinkedHashMap<>();
                    nd.put("id", nodeId); nd.put("type", "birth"); nd.put("level", levelVal); nd.put("size", curComp.size());
                    nodes.add(nd);
                    for (int gid : curComp) curNodeIds.put(gid, nodeId);
                    nodeId++;
                } else if (parentNodes.size() > 1) {
                    Map<String, Object> nd = new LinkedHashMap<>();
                    nd.put("id", nodeId); nd.put("type", "merge"); nd.put("level", levelVal);
                    nd.put("size", curComp.size()); nd.put("parents", new ArrayList<>(parentNodes));
                    nodes.add(nd);
                    for (int pid : parentNodes) arcs.add(Map.of("from", pid, "to", nodeId));
                    for (int gid : curComp) curNodeIds.put(gid, nodeId);
                    nodeId++;
                } else {
                    int pid = parentNodes.iterator().next();
                    for (int gid : curComp) curNodeIds.put(gid, pid);
                }
            }
            prevNodeMap = curNodeIds;
            prevComps = curComps;
        }

        int births = 0, merges = 0, splits = 0;
        for (Map<String, Object> nd : nodes) {
            switch ((String) nd.get("type")) {
                case "birth" -> births++;
                case "merge" -> merges++;
                case "split" -> splits++;
            }
        }
        String complexity = (merges == 0 && splits == 0) ? "simple" : (merges + splits <= 3 ? "moderate" : "complex");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("target_field", targetField); r.put("n_levels", thresholds.size() - 1);
        r.put("nodes", nodes.subList(0, Math.min(50, nodes.size())));
        r.put("arcs", arcs.subList(0, Math.min(50, arcs.size())));
        r.put("summary", Map.of("births", births, "merges", merges, "splits", splits,
                "total_nodes", nodes.size(), "total_arcs", arcs.size(), "complexity_label", complexity));
        r.put("value_range", List.of(r6(valued.get(0)[0]), r6(valued.get(vn - 1)[0])));
        return r;
    }

    // ── vector field analysis ───────────────────────────────────────────────

    public static Map<String, Object> vectorFieldAnalysis(ManifoldSurface surface, String targetField, int topK) {
        Map<Integer, GridPoint> idx = idxMap(surface);
        Map<Integer, Double> divergences = new LinkedHashMap<>();
        Map<Integer, Double> curlScores = new LinkedHashMap<>();
        for (GridPoint gp : surface.getGrid().values()) {
            Double myVal = gp.getFieldValues().get(targetField);
            if (myVal == null || gp.getNeighbors().isEmpty()) continue;
            double outFlux = 0; List<Double> ngrads = new ArrayList<>();
            for (int ni : gp.getNeighbors()) {
                GridPoint np = idx.get(ni); if (np == null) continue;
                Double nv = np.getFieldValues().get(targetField); if (nv == null) continue;
                double g = nv - myVal; outFlux += g; ngrads.add(g);
            }
            divergences.put(gp.getIndex(), outFlux / Math.max(gp.getNeighbors().size(), 1));
            if (ngrads.size() >= 3) {
                int sc = 0;
                for (int i = 0; i < ngrads.size(); i++) {
                    if ((ngrads.get(i) > 0) != (ngrads.get((i + 1) % ngrads.size()) > 0)) sc++;
                }
                curlScores.put(gp.getIndex(), (double) sc / ngrads.size());
            } else curlScores.put(gp.getIndex(), 0.0);
        }
        if (divergences.isEmpty()) return Map.of("error", "No computable gradients.");
        double mean = divergences.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double std = Math.sqrt(divergences.values().stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0));
        List<Map<String, Object>> sources = new ArrayList<>(), sinks = new ArrayList<>(), vortices = new ArrayList<>();
        for (GridPoint gp : surface.getGrid().values()) {
            Double d = divergences.get(gp.getIndex()); if (d == null) continue;
            double c = curlScores.getOrDefault(gp.getIndex(), 0.0);
            double z = std > 1e-12 ? (d - mean) / std : 0;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("coord", gp.getAxisCoords()); entry.put("divergence", r6(d)); entry.put("curl_score", r4(c));
            if (gp.getFieldValues().containsKey(targetField)) entry.put("value", r6(gp.getFieldValues().get(targetField)));
            if (z > 1.5) sources.add(entry);
            else if (z < -1.5) sinks.add(entry);
            if (c > 0.6 && Math.abs(z) <= 1.5) vortices.add(entry);
        }
        sources.sort((a, b) -> Double.compare(Math.abs((double) b.get("divergence")), Math.abs((double) a.get("divergence"))));
        sinks.sort((a, b) -> Double.compare(Math.abs((double) b.get("divergence")), Math.abs((double) a.get("divergence"))));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("target_field", targetField); r.put("vertices_analyzed", divergences.size());
        r.put("mean_divergence", r6(mean)); r.put("std_divergence", r6(std));
        r.put("sources", sources.subList(0, Math.min(topK, sources.size())));
        r.put("sinks", sinks.subList(0, Math.min(topK, sinks.size())));
        r.put("vortices", vortices.subList(0, Math.min(topK, vortices.size())));
        r.put("flow_summary", Map.of("n_sources", sources.size(), "n_sinks", sinks.size(),
                "n_vortices", vortices.size(), "has_rotational_structure", !vortices.isEmpty()));
        return r;
    }

    // ── laplacian spectrum ──────────────────────────────────────────────────

    public static Map<String, Object> laplacianSpectrum(ManifoldSurface surface, int nEigen, Integer nClusters) {
        if (surface.getVertexCount() < 4) return Map.of("error", "Grid too small.");
        List<Integer> ordered = new ArrayList<>(); Map<Integer, Integer> pos = new HashMap<>();
        prepareOrdered(surface, ordered, pos);
        int n = ordered.size(); int nEig = Math.min(nEigen, n - 1);
        RealMatrix L = buildGraphLaplacian(surface, ordered, pos);
        EigenDecomposition ed = eigenDecompose(L);
        int[] sortIdx = sortedEigenIndices(ed, n);
        double[] evals = new double[nEig];
        for (int k = 0; k < nEig; k++) evals[k] = ed.getRealEigenvalue(sortIdx[k]);
        double spectralGap = nEig > 1 ? evals[1] : 0;
        String connLabel = spectralGap < 1e-6 ? "disconnected" : spectralGap < 0.5 ? "weakly_connected"
                : spectralGap < 2 ? "moderately_connected" : "strongly_connected";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("vertices", n);
        List<Double> evalList = new ArrayList<>(); for (double e : evals) evalList.add(r6(e));
        r.put("eigenvalues", evalList); r.put("spectral_gap", r6(spectralGap));
        r.put("connectivity_label", connLabel);
        return r;
    }

    // ── scalar harmonics ────────────────────────────────────────────────────

    public static Map<String, Object> scalarHarmonics(ManifoldSurface surface, String targetField, int nModes, int topKAnomalies) {
        List<Integer> ordered = new ArrayList<>(); Map<Integer, Integer> pos = new HashMap<>();
        prepareOrdered(surface, ordered, pos);
        Map<Integer, GridPoint> idx = idxMap(surface);
        int n = ordered.size();
        double[] signal = new double[n]; int valid = 0;
        for (int i = 0; i < n; i++) {
            Double v = idx.get(ordered.get(i)).getFieldValues().get(targetField);
            if (v != null) { signal[i] = v; valid++; }
        }
        if (valid < 4) return Map.of("error", "Not enough values.");
        nModes = Math.min(nModes, n - 1);
        RealMatrix L = buildGraphLaplacian(surface, ordered, pos);
        EigenDecomposition ed = eigenDecompose(L);
        int[] sortIdx = sortedEigenIndices(ed, n);
        double[] evals = new double[nModes]; double[][] evecs = new double[n][nModes];
        for (int k = 0; k < nModes; k++) {
            evals[k] = ed.getRealEigenvalue(sortIdx[k]);
            double[] v = ed.getEigenvector(sortIdx[k]).toArray();
            for (int i = 0; i < n; i++) evecs[i][k] = v[i];
        }
        double[] coeffs = new double[nModes];
        for (int k = 0; k < nModes; k++) { double s = 0; for (int i = 0; i < n; i++) s += evecs[i][k] * signal[i]; coeffs[k] = s; }
        double[] smooth = new double[n];
        for (int i = 0; i < n; i++) { double s = 0; for (int k = 0; k < nModes; k++) s += evecs[i][k] * coeffs[k]; smooth[i] = s; }
        double[] residual = new double[n];
        for (int i = 0; i < n; i++) residual[i] = signal[i] - smooth[i];
        double totalEnergy = 0; for (double c : coeffs) totalEnergy += c * c;
        if (totalEnergy < 1e-12) totalEnergy = 1;
        List<Map<String, Object>> spectrum = new ArrayList<>(); double cum = 0;
        for (int k = 0; k < nModes; k++) {
            double frac = coeffs[k] * coeffs[k] / totalEnergy; cum += frac;
            spectrum.add(Map.of("mode", k, "eigenvalue", r6(evals[k]), "energy_fraction", r6(frac), "cumulative", r6(cum)));
        }
        Integer[] anomIdx = new Integer[n]; for (int i = 0; i < n; i++) anomIdx[i] = i;
        Arrays.sort(anomIdx, (a, b) -> Double.compare(Math.abs(residual[b]), Math.abs(residual[a])));
        List<Map<String, Object>> anomalies = new ArrayList<>();
        for (int k = 0; k < Math.min(topKAnomalies, n); k++) {
            int ai = anomIdx[k]; if (Math.abs(residual[ai]) < 1e-9) break;
            GridPoint gp = idx.get(ordered.get(ai));
            anomalies.add(Map.of("coord", gp.getAxisCoords(), "observed", r6(signal[ai]),
                    "smooth_prediction", r6(smooth[ai]), "residual", r6(residual[ai])));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("target_field", targetField); r.put("modes_computed", nModes);
        r.put("energy_spectrum", spectrum); r.put("anomalies", anomalies);
        return r;
    }

    // ── diffusion distance ──────────────────────────────────────────────────

    public static Map<String, Object> diffusionDistance(
            ManifoldSurface surface, List<List<String>> landmarkCoords, double timeParam, int nEigen, int nLandmarks) {
        if (surface.getVertexCount() < 4) return Map.of("error", "Grid too small.");
        List<Integer> ordered = new ArrayList<>(); Map<Integer, Integer> pos = new HashMap<>();
        prepareOrdered(surface, ordered, pos);
        int n = ordered.size(); int nEig = Math.min(nEigen, n - 1);
        RealMatrix L = buildGraphLaplacian(surface, ordered, pos);
        EigenDecomposition ed = eigenDecompose(L);
        int[] sortIdx = sortedEigenIndices(ed, n);
        double[] evals = new double[nEig]; double[][] evecs = new double[n][nEig];
        for (int k = 0; k < nEig; k++) {
            evals[k] = ed.getRealEigenvalue(sortIdx[k]);
            double[] v = ed.getEigenvector(sortIdx[k]).toArray();
            for (int i = 0; i < n; i++) evecs[i][k] = v[i];
        }
        List<Integer> lmPos;
        if (landmarkCoords != null && !landmarkCoords.isEmpty()) {
            lmPos = new ArrayList<>();
            for (List<String> lc : landmarkCoords) {
                GridPoint gp = surface.getGrid().get(lc);
                if (gp != null && pos.containsKey(gp.getIndex())) lmPos.add(pos.get(gp.getIndex()));
            }
            if (lmPos.size() < 2) return Map.of("error", "Need >= 2 valid landmarks.");
        } else {
            int nLm = Math.min(nLandmarks, n);
            int step = Math.max(1, n / nLm);
            lmPos = new ArrayList<>();
            for (int i = 0; i < n && lmPos.size() < nLm; i += step) lmPos.add(i);
        }
        double[][] dc = new double[n][nEig];
        for (int k = 0; k < nEig; k++) {
            if (evals[k] < 1e-12) continue;
            double scale = Math.exp(-evals[k] * timeParam);
            for (int i = 0; i < n; i++) dc[i][k] = scale * evecs[i][k];
        }
        int nLm = lmPos.size();
        Map<Integer, GridPoint> idx = idxMap(surface);
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (int i = 0; i < nLm; i++) {
            for (int j = i + 1; j < nLm; j++) {
                double d = 0; for (int k = 0; k < nEig; k++) { double diff = dc[lmPos.get(i)][k] - dc[lmPos.get(j)][k]; d += diff * diff; }
                d = Math.sqrt(d);
                pairs.add(Map.of("from", idx.get(ordered.get(lmPos.get(i))).getAxisCoords(),
                        "to", idx.get(ordered.get(lmPos.get(j))).getAxisCoords(), "diffusion_distance", r6(d)));
            }
        }
        pairs.sort(Comparator.comparingDouble(p -> (double) p.get("diffusion_distance")));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("time_param", timeParam); r.put("n_landmarks", nLm);
        r.put("distance_pairs", pairs.subList(0, Math.min(20, pairs.size())));
        return r;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<Set<Integer>> connectedComponents(Set<Integer> active, Map<Integer, GridPoint> idx) {
        if (active.isEmpty()) return List.of();
        Set<Integer> seen = new HashSet<>(); List<Set<Integer>> comps = new ArrayList<>();
        for (int start : active) {
            if (seen.contains(start)) continue;
            Set<Integer> comp = new HashSet<>(); Deque<Integer> q = new ArrayDeque<>();
            q.add(start); seen.add(start);
            while (!q.isEmpty()) {
                int cur = q.poll(); comp.add(cur);
                GridPoint gp = idx.get(cur); if (gp == null) continue;
                for (int ni : gp.getNeighbors()) { if (active.contains(ni) && seen.add(ni)) q.add(ni); }
            }
            comps.add(comp);
        }
        return comps;
    }

    private static double r6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
    private static double r4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
