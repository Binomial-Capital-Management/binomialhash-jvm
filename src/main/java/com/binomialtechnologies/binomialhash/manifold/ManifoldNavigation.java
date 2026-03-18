package com.binomialtechnologies.binomialhash.manifold;

import java.util.*;

/**
 * Navigation and pathfinding on manifold grid surfaces:
 * Dijkstra, BFS, orbit, basin, trace, controlled walk, etc.
 */
public final class ManifoldNavigation {

    private ManifoldNavigation() {}

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<Integer, GridPoint> idxMap(ManifoldSurface surface) {
        Map<Integer, GridPoint> m = new HashMap<>();
        for (GridPoint gp : surface.getGrid().values()) m.put(gp.getIndex(), gp);
        return m;
    }

    // ── Dijkstra ────────────────────────────────────────────────────────────

    public static class PathResult {
        public final List<Integer> path;
        public final double cost;
        public PathResult(List<Integer> path, double cost) { this.path = path; this.cost = cost; }
    }

    /** Weight = |Δ target| + ε per edge, or 1.0 if no target. */
    public static PathResult dijkstra(
            Map<Integer, GridPoint> idxToPoint, int startIdx, int endIdx, String targetField) {
        Map<Integer, Double> dist = new HashMap<>();
        Map<Integer, Integer> prev = new HashMap<>();
        PriorityQueue<double[]> heap = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        Set<Integer> visited = new HashSet<>();
        dist.put(startIdx, 0.0);
        heap.add(new double[]{0.0, startIdx});

        while (!heap.isEmpty()) {
            double[] top = heap.poll();
            int cur = (int) top[1]; double d = top[0];
            if (!visited.add(cur)) continue;
            if (cur == endIdx) break;
            GridPoint pt = idxToPoint.get(cur);
            if (pt == null) continue;
            for (int ni : pt.getNeighbors()) {
                if (visited.contains(ni)) continue;
                GridPoint np = idxToPoint.get(ni);
                if (np == null) continue;
                double w;
                if (targetField != null) {
                    double cv = pt.getFieldValues().getOrDefault(targetField, 0.0);
                    double nv = np.getFieldValues().getOrDefault(targetField, 0.0);
                    w = Math.abs(nv - cv) + 1e-6;
                } else w = 1.0;
                double nd = d + w;
                if (nd < dist.getOrDefault(ni, Double.MAX_VALUE)) {
                    dist.put(ni, nd);
                    prev.put(ni, cur);
                    heap.add(new double[]{nd, ni});
                }
            }
        }
        if (!dist.containsKey(endIdx)) return null;
        List<Integer> path = new ArrayList<>();
        int c = endIdx;
        while (c != startIdx) { path.add(c); Integer p = prev.get(c); if (p == null) return null; c = p; }
        path.add(startIdx);
        Collections.reverse(path);
        return new PathResult(path, dist.get(endIdx));
    }

    // ── geodesic_path ───────────────────────────────────────────────────────

    public static Map<String, Object> geodesicPath(
            ManifoldSurface surface, List<String> start, List<String> end, String targetField) {
        Map<List<String>, GridPoint> grid = surface.getGrid();
        if (!grid.containsKey(start)) return Map.of("error", "Start not in grid.");
        if (!grid.containsKey(end))   return Map.of("error", "End not in grid.");
        if (start.equals(end)) {
            GridPoint pt = grid.get(start);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("hops", 0); r.put("total_cost", 0.0);
            r.put("waypoints", List.of(Map.of("coord", start, "fields", rounded(pt.getFieldValues()))));
            return r;
        }
        Map<Integer, GridPoint> idx = idxMap(surface);
        PathResult pr = dijkstra(idx, grid.get(start).getIndex(), grid.get(end).getIndex(), targetField);
        if (pr == null) return Map.of("error", "No path found (disconnected components).");
        List<Map<String, Object>> waypoints = new ArrayList<>();
        for (int i = 0; i < pr.path.size(); i++) {
            GridPoint pt = idx.get(pr.path.get(i));
            Map<String, Object> wp = new LinkedHashMap<>();
            wp.put("step", i); wp.put("coord", pt.getAxisCoords());
            wp.put("fields", rounded(pt.getFieldValues())); wp.put("density", pt.getDensity());
            if (targetField != null) wp.put("morse_type", pt.getMorseType().get(targetField));
            if (i > 0) {
                GridPoint prev = idx.get(pr.path.get(i - 1));
                Map<String, Double> deltas = new LinkedHashMap<>();
                for (String f : surface.getFieldColumns()) {
                    Double pv = prev.getFieldValues().get(f), cv = pt.getFieldValues().get(f);
                    if (pv != null && cv != null && Math.abs(cv - pv) > 1e-9) deltas.put(f, r6(cv - pv));
                }
                wp.put("delta_from_prev", deltas);
            }
            waypoints.add(wp);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("start", start); r.put("end", end); r.put("hops", waypoints.size() - 1);
        r.put("total_cost", r6(pr.cost)); r.put("weight", targetField != null ? targetField : "hop_count");
        r.put("waypoints", waypoints);
        return r;
    }

    // ── controlled walk ────────────────────────────────────────────────────

    public static Map<String, Object> controlledWalk(ManifoldSurface surface, String walkAxis, String targetField) {
        int axisIdx = -1;
        for (int i = 0; i < surface.getAxes().size(); i++) {
            if (surface.getAxes().get(i).getColumn().equals(walkAxis)) { axisIdx = i; break; }
        }
        if (axisIdx < 0) return Map.of("error", "Axis '" + walkAxis + "' not found.");
        ManifoldAxis axis = surface.getAxes().get(axisIdx);
        Map<String, List<Double>> buckets = new LinkedHashMap<>();
        for (GridPoint pt : surface.getGrid().values()) {
            String av = pt.getAxisCoords().get(axisIdx);
            Double tv = pt.getFieldValues().get(targetField);
            if (tv != null) buckets.computeIfAbsent(av, k -> new ArrayList<>()).add(tv);
        }
        if (buckets.isEmpty()) return Map.of("error", "No data for axis/field.");
        List<Map<String, Object>> profile = new ArrayList<>();
        for (Object v : axis.getValues()) {
            String sv = String.valueOf(v);
            if (!buckets.containsKey(sv)) continue;
            List<Double> vals = buckets.get(sv);
            double mean = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("axis_value", sv); entry.put("mean", r6(mean));
            entry.put("min", r6(vals.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
            entry.put("max", r6(vals.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
            entry.put("points", vals.size());
            profile.add(entry);
        }
        double maxMean = profile.stream().mapToDouble(m -> (double) m.get("mean")).max().orElse(0);
        double minMean = profile.stream().mapToDouble(m -> (double) m.get("mean")).min().orElse(0);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("axis", walkAxis); r.put("target_field", targetField);
        r.put("steps", profile.size()); r.put("sensitivity", r6(maxMean - minMean));
        r.put("profile", profile);
        return r;
    }

    // ── orbit ──────────────────────────────────────────────────────────────

    public static Map<String, Object> orbit(
            ManifoldSurface surface, List<String> center, int radius,
            String targetField, int resolution, String mode) {
        if (radius < 1) return Map.of("error", "radius must be >= 1");
        GridPoint cp = surface.getGrid().get(center);
        if (cp == null) return Map.of("error", "Center not in grid.");
        Map<Integer, GridPoint> idx = idxMap(surface);
        Map<Integer, Integer> dists = bfsDist(idx, cp.getIndex(), radius);
        Map<Integer, List<GridPoint>> shells = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : dists.entrySet()) {
            shells.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(idx.get(e.getKey()));
        }
        List<GridPoint> selected = "ring".equals(mode) ? shells.getOrDefault(radius, List.of())
                : dists.entrySet().stream().filter(e -> e.getValue() <= radius)
                        .map(e -> idx.get(e.getKey())).toList();
        List<Map<String, Object>> shellProfiles = new ArrayList<>();
        for (Map.Entry<Integer, List<GridPoint>> e : shells.entrySet()) {
            Map<String, Object> sp = summarize(surface, e.getValue(), targetField);
            sp.put("distance", e.getKey());
            shellProfiles.add(sp);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("center", center); r.put("mode", mode); r.put("radius", radius);
        r.put("target_field", targetField);
        r.put("center_summary", summarize(surface, List.of(cp), targetField));
        r.put("zone_summary", summarize(surface, selected, targetField));
        r.put("shell_profiles", shellProfiles);
        return r;
    }

    // ── basin ──────────────────────────────────────────────────────────────

    public static Map<String, Object> basin(
            ManifoldSurface surface, List<String> seed, String targetField, String direction) {
        GridPoint sp = surface.getGrid().get(seed);
        if (sp == null) return Map.of("error", "Seed not in grid.");
        Map<Integer, GridPoint> idx = idxMap(surface);
        List<String> extremum = flow(idx, sp, targetField, "ascend".equals(direction));
        if (extremum == null) return Map.of("error", "No target field at seed.");
        List<GridPoint> members = new ArrayList<>();
        for (GridPoint pt : surface.getGrid().values()) {
            if (extremum.equals(flow(idx, pt, targetField, "ascend".equals(direction)))) members.add(pt);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("seed", seed); r.put("target_field", targetField); r.put("direction", direction);
        r.put("extremum_coord", extremum);
        r.put("basin_summary", summarize(surface, members, targetField));
        List<List<String>> preview = new ArrayList<>();
        for (int i = 0; i < Math.min(20, members.size()); i++) preview.add(members.get(i).getAxisCoords());
        r.put("members_preview", preview);
        return r;
    }

    // ── trace extremum ─────────────────────────────────────────────────────

    public static Map<String, Object> traceExtremum(
            ManifoldSurface surface, List<String> seed, String targetField, String mode, int maxSteps) {
        GridPoint pt = surface.getGrid().get(seed);
        if (pt == null) return Map.of("error", "Seed not in grid.");
        Map<Integer, GridPoint> idx = idxMap(surface);
        boolean ascend = "ridge".equals(mode);
        List<GridPoint> path = new ArrayList<>();
        path.add(pt); Set<Integer> visited = new HashSet<>(); visited.add(pt.getIndex());
        GridPoint cur = pt;
        for (int s = 0; s < maxSteps; s++) {
            Double myVal = cur.getFieldValues().get(targetField);
            if (myVal == null) break;
            GridPoint best = null; double bestVal = myVal;
            for (int ni : cur.getNeighbors()) {
                GridPoint np = idx.get(ni);
                if (np == null || visited.contains(np.getIndex())) continue;
                Double nv = np.getFieldValues().get(targetField);
                if (nv == null) continue;
                if ((ascend && nv > bestVal) || (!ascend && nv < bestVal)) { bestVal = nv; best = np; }
            }
            if (best == null) break;
            path.add(best); visited.add(best.getIndex()); cur = best;
        }
        List<Map<String, Object>> pathOut = new ArrayList<>();
        for (GridPoint p : path) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("coord", p.getAxisCoords());
            m.put("value", r6(p.getFieldValues().getOrDefault(targetField, 0.0)));
            m.put("curvature", r6(p.getCurvature()));
            pathOut.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("seed", seed); r.put("mode", mode); r.put("target_field", targetField);
        r.put("steps", path.size() - 1); r.put("path", pathOut);
        return r;
    }

    // ── navigate ───────────────────────────────────────────────────────────

    public static Map<String, Object> navigate(ManifoldSurface surface, List<String> coord, String targetField) {
        GridPoint pt = surface.getGrid().get(coord);
        if (pt == null) return Map.of("error", "No grid point at " + coord);
        Map<Integer, GridPoint> idx = idxMap(surface);
        List<GridPoint> neighbors = new ArrayList<>();
        for (int ni : pt.getNeighbors()) { GridPoint np = idx.get(ni); if (np != null) neighbors.add(np); }

        Map<String, Map<String, Object>> gradients = new LinkedHashMap<>();
        if (targetField != null && pt.getFieldValues().containsKey(targetField)) {
            double myVal = pt.getFieldValues().get(targetField);
            for (GridPoint n : neighbors) {
                Double nv = n.getFieldValues().get(targetField);
                if (nv == null) continue;
                List<String> dirs = new ArrayList<>();
                for (int i = 0; i < surface.getAxes().size(); i++) {
                    if (!pt.getAxisCoords().get(i).equals(n.getAxisCoords().get(i))) {
                        dirs.add(surface.getAxes().get(i).getColumn() + ": " +
                                pt.getAxisCoords().get(i) + " -> " + n.getAxisCoords().get(i));
                    }
                }
                String dirKey = dirs.isEmpty() ? "same" : String.join(", ", dirs);
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("delta", r6(nv - myVal)); g.put("neighbor_value", r6(nv));
                g.put("neighbor_curvature", r6(n.getCurvature()));
                gradients.put(dirKey, g);
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("coord", pt.getAxisCoords()); pos.put("field_values", rounded(pt.getFieldValues()));
        pos.put("curvature", r6(pt.getCurvature())); pos.put("density", pt.getDensity());
        pos.put("neighbor_count", pt.getNeighbors().size());
        r.put("position", pos); r.put("surface", surface.getSurfaceName());
        r.put("gradients", gradients);
        return r;
    }

    // ── wrap / coverage audits ─────────────────────────────────────────────

    public static Map<String, Object> wrapAudit(ManifoldSurface surface) {
        List<Map<String, Object>> ax = new ArrayList<>();
        for (ManifoldAxis a : surface.getAxes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("axis", a.getColumn()); m.put("wraps", a.isWraps());
            m.put("wrap_orientation", a.getWrapOrientation());
            ax.add(m);
        }
        return Map.of("axes", ax, "surface_confidence", surface.getSurfaceConfidenceObj());
    }

    public static Map<String, Object> coverageAudit(ManifoldSurface surface) {
        List<Integer> dens = new ArrayList<>();
        int isolated = 0;
        for (GridPoint pt : surface.getGrid().values()) {
            dens.add(pt.getDensity());
            if (pt.getNeighbors().isEmpty()) isolated++;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("vertices", surface.getVertexCount()); graph.put("edges", surface.getEdgeCount());
        graph.put("components", surface.getBetti0()); graph.put("isolated_vertices", isolated);
        r.put("graph", graph);
        double mean = dens.isEmpty() ? 0 : dens.stream().mapToInt(Integer::intValue).average().orElse(0);
        r.put("density", Map.of("mean", r6(mean),
                "min", dens.isEmpty() ? 0 : Collections.min(dens),
                "max", dens.isEmpty() ? 0 : Collections.max(dens)));
        return r;
    }

    // ── private helpers ────────────────────────────────────────────────────

    private static Map<Integer, Integer> bfsDist(Map<Integer, GridPoint> idx, int startIdx, int maxHops) {
        Map<Integer, Integer> dists = new LinkedHashMap<>();
        dists.put(startIdx, 0);
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(startIdx);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (dists.get(cur) >= maxHops) continue;
            GridPoint cp = idx.get(cur);
            if (cp == null) continue;
            for (int ni : cp.getNeighbors()) {
                if (idx.containsKey(ni) && !dists.containsKey(ni)) {
                    dists.put(ni, dists.get(cur) + 1);
                    queue.add(ni);
                }
            }
        }
        return dists;
    }

    private static List<String> flow(Map<Integer, GridPoint> idx, GridPoint start, String field, boolean ascend) {
        GridPoint cur = start; Set<Integer> visited = new HashSet<>(); visited.add(cur.getIndex());
        for (int i = 0; i < 100; i++) {
            Double myVal = cur.getFieldValues().get(field);
            if (myVal == null) return null;
            GridPoint best = null; double bestVal = myVal;
            for (int ni : cur.getNeighbors()) {
                GridPoint np = idx.get(ni);
                if (np == null) continue;
                Double nv = np.getFieldValues().get(field);
                if (nv == null) continue;
                if (ascend ? nv > bestVal : nv < bestVal) { bestVal = nv; best = np; }
            }
            if (best == null || visited.contains(best.getIndex())) return cur.getAxisCoords();
            visited.add(best.getIndex()); cur = best;
        }
        return cur.getAxisCoords();
    }

    static Map<String, Object> summarize(ManifoldSurface surface, List<GridPoint> points, String targetField) {
        if (points.isEmpty()) return Map.of("points", 0);
        double sumCurv = 0, sumDens = 0;
        for (GridPoint p : points) { sumCurv += p.getCurvature(); sumDens += p.getDensity(); }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("points", points.size());
        r.put("mean_curvature", r6(sumCurv / points.size()));
        r.put("mean_density", r6(sumDens / points.size()));
        if (targetField != null) {
            List<Double> vals = new ArrayList<>();
            for (GridPoint p : points) {
                Double v = p.getFieldValues().get(targetField);
                if (v != null) vals.add(v);
            }
            if (!vals.isEmpty()) {
                r.put("target_field", targetField);
                r.put("mean_value", r6(vals.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                r.put("min_value", r6(Collections.min(vals)));
                r.put("max_value", r6(Collections.max(vals)));
            }
        }
        return r;
    }

    private static Map<String, Double> rounded(Map<String, Double> m) {
        Map<String, Double> r = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : m.entrySet()) r.put(e.getKey(), r6(e.getValue()));
        return r;
    }

    private static double r6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
}
