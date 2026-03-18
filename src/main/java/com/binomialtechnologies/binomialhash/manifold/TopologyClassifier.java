package com.binomialtechnologies.binomialhash.manifold;

import java.util.*;

/**
 * Surface classification from the product of per-axis boundary types,
 * and 2D face-complex topology computation.
 */
public final class TopologyClassifier {

    private TopologyClassifier() {}

    public static class TopologyResult {
        public final String surfaceName;
        public final int genus;
        public final boolean orientable;
        public final int eulerCharacteristic;
        public TopologyResult(String surfaceName, int genus, boolean orientable, int eulerCharacteristic) {
            this.surfaceName = surfaceName;
            this.genus = genus;
            this.orientable = orientable;
            this.eulerCharacteristic = eulerCharacteristic;
        }
    }

    /** Classify surface from the product of per-axis boundary types. */
    public static TopologyResult classifyProductTopology(List<ManifoldAxis> axes) {
        int nC = 0, nX = 0, nI = 0;
        for (ManifoldAxis axis : axes) {
            if (axis.isWraps() && axis.getWrapOrientation() == -1) nX++;
            else if (axis.isWraps() && axis.getWrapOrientation() == 1) nC++;
            else nI++;
        }

        if (nX == 0 && nC == 0) return new TopologyResult("bounded_patch", 0, true, 2);
        if (nX == 0 && nC == 1) return new TopologyResult("cylinder", 0, true, 0);
        if (nX == 0 && nC >= 2) return new TopologyResult("torus", 1, true, 0);
        if (nX >= 1 && nC == 0 && nI >= 1) return new TopologyResult("mobius_band", 1, false, 0);
        if (nX >= 1 && nC >= 1) return new TopologyResult("klein_bottle", 2, false, 0);
        if (nX >= 2 && nC == 0 && nI == 0) return new TopologyResult("klein_bottle", 2, false, 0);
        return new TopologyResult("bounded_patch", 0, true, 2);
    }

    /** Count connected components in an undirected edge set. */
    public static int countEdgeComponents(List<int[]> edges) {
        if (edges.isEmpty()) return 0;

        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (int[] e : edges) {
            adj.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
            adj.computeIfAbsent(e[1], k -> new ArrayList<>()).add(e[0]);
        }

        Set<Integer> seen = new HashSet<>();
        int components = 0;
        for (int start : adj.keySet()) {
            if (seen.contains(start)) continue;
            components++;
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen.add(start);
            while (!queue.isEmpty()) {
                int current = queue.poll();
                for (int neighbor : adj.getOrDefault(current, Collections.emptyList())) {
                    if (seen.add(neighbor)) queue.add(neighbor);
                }
            }
        }
        return components;
    }

    /**
     * Build square faces on a 2D grid and compute face-based diagnostics.
     * Returns null if axes.size() != 2.
     */
    public static Map<String, Object> computeFaceTopology2d(
            Map<List<String>, GridPoint> grid, List<ManifoldAxis> axes) {
        if (axes.size() != 2) return null;

        ManifoldAxis axis0 = axes.get(0), axis1 = axes.get(1);
        int m = axis0.getSize(), n = axis1.getSize();
        if (m < 2 || n < 2) return null;

        Map<List<String>, Integer> coordToIdx = new LinkedHashMap<>();
        for (Map.Entry<List<String>, GridPoint> e : grid.entrySet()) {
            coordToIdx.put(e.getKey(), e.getValue().getIndex());
        }

        int cellsU = axis0.isWraps() ? m : m - 1;
        int cellsV = axis1.isWraps() ? n : n - 1;
        int possibleFaces = Math.max(cellsU, 0) * Math.max(cellsV, 0);
        if (possibleFaces <= 0) return null;

        List<String> values0 = new ArrayList<>();
        for (Object v : axis0.getValues()) values0.add(String.valueOf(v));
        List<String> values1 = new ArrayList<>();
        for (Object v : axis1.getValues()) values1.add(String.valueOf(v));

        int faceCount = 0;
        Map<List<Integer>, Integer> edgeFaceIncidence = new LinkedHashMap<>();
        Set<Integer> faceVertices = new HashSet<>();

        for (int i = 0; i < cellsU; i++) {
            for (int j = 0; j < cellsV; j++) {
                int i2 = (i + 1) % m;
                int j2 = (j + 1) % n;

                List<String> c00 = List.of(values0.get(i), values1.get(j));
                List<String> c10 = List.of(values0.get(i2), values1.get(j));
                List<String> c01 = List.of(values0.get(i), values1.get(j2));
                List<String> c11 = List.of(values0.get(i2), values1.get(j2));

                if (!coordToIdx.containsKey(c00) || !coordToIdx.containsKey(c10)
                        || !coordToIdx.containsKey(c01) || !coordToIdx.containsKey(c11)) continue;

                int v00 = coordToIdx.get(c00), v10 = coordToIdx.get(c10);
                int v01 = coordToIdx.get(c01), v11 = coordToIdx.get(c11);

                faceCount++;
                faceVertices.addAll(List.of(v00, v10, v01, v11));

                for (List<Integer> edge : List.of(
                        sortedEdge(v00, v10), sortedEdge(v10, v11),
                        sortedEdge(v11, v01), sortedEdge(v01, v00))) {
                    edgeFaceIncidence.merge(edge, 1, Integer::sum);
                }
            }
        }

        if (faceCount == 0) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("mode", "graph_heuristic");
            r.put("faces", 0);
            r.put("possible_faces", possibleFaces);
            r.put("face_coverage", 0.0);
            r.put("occupancy", (double) grid.size() / Math.max(m * n, 1));
            r.put("face_vertex_count", 0);
            r.put("face_edge_count", 0);
            r.put("boundary_edges", 0);
            r.put("boundary_loops", 0);
            r.put("face_euler_characteristic", null);
            r.put("face_genus_estimate_orientable", null);
            r.put("is_manifold", false);
            r.put("nonmanifold_edges", 0);
            return r;
        }

        int edgeCountFaces = edgeFaceIncidence.size();
        int vertexCountFaces = faceVertices.size();
        int chiFace = vertexCountFaces - edgeCountFaces + faceCount;

        List<int[]> boundaryEdges = new ArrayList<>();
        for (Map.Entry<List<Integer>, Integer> e : edgeFaceIncidence.entrySet()) {
            if (e.getValue() == 1) {
                boundaryEdges.add(new int[]{e.getKey().get(0), e.getKey().get(1)});
            }
        }
        int boundaryEdgeCount = boundaryEdges.size();
        int boundaryLoops = countEdgeComponents(boundaryEdges);
        int nonmanifoldEdgeCount = 0;
        for (int count : edgeFaceIncidence.values()) {
            if (count > 2) nonmanifoldEdgeCount++;
        }
        boolean isManifold = nonmanifoldEdgeCount == 0;

        int boundaryComponents = boundaryEdgeCount > 0 ? boundaryLoops : 0;
        double genusEstOrientable = (2.0 - boundaryComponents - chiFace) / 2.0;

        double faceCoverage = (double) faceCount / Math.max(possibleFaces, 1);
        double occupancy = (double) grid.size() / Math.max(m * n, 1);
        double confidence = Math.max(0.0, Math.min(1.0, 0.55 * faceCoverage + 0.45 * occupancy));
        if (!isManifold) confidence *= 0.5;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", isManifold ? "validated_2d_faces" : "nonmanifold_complex");
        result.put("faces", faceCount);
        result.put("possible_faces", possibleFaces);
        result.put("face_coverage", Math.round(faceCoverage * 10000.0) / 10000.0);
        result.put("occupancy", Math.round(occupancy * 10000.0) / 10000.0);
        result.put("face_vertex_count", vertexCountFaces);
        result.put("face_edge_count", edgeCountFaces);
        result.put("boundary_edges", boundaryEdgeCount);
        result.put("boundary_loops", boundaryLoops);
        result.put("face_euler_characteristic", chiFace);
        result.put("face_genus_estimate_orientable", Math.round(genusEstOrientable * 1_000_000.0) / 1_000_000.0);
        result.put("confidence", Math.round(confidence * 10000.0) / 10000.0);
        result.put("is_manifold", isManifold);
        result.put("nonmanifold_edges", nonmanifoldEdgeCount);
        return result;
    }

    private static List<Integer> sortedEdge(int a, int b) {
        return a <= b ? List.of(a, b) : List.of(b, a);
    }
}
