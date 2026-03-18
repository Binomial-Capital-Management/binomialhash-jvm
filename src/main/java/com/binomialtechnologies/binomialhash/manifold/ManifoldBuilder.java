package com.binomialtechnologies.binomialhash.manifold;

import java.util.*;
import java.util.logging.Logger;

/**
 * ManifoldSurface builder: constructs the full manifold from tabular data.
 *
 * <p>Axes are inferred from discovered columns and values, then projected into a
 * coordinate grid with adjacency, field summaries, and face-complex diagnostics.</p>
 */
public final class ManifoldBuilder {

    private static final Logger LOG = Logger.getLogger(ManifoldBuilder.class.getName());

    private ManifoldBuilder() {}

    /**
     * Build the manifold surface from BH slot data.
     *
     * @return ManifoldSurface or null if data is insufficient
     */
    public static ManifoldSurface buildManifold(
            List<Map<String, Object>> rows,
            List<String> columns,
            Map<String, String> colTypes,
            Map<String, Map<String, Object>> colStats) {

        if (rows.size() < 10) return null;

        AxesIdentifier.AxesResult ar = AxesIdentifier.identifyAxes(rows, columns, colTypes, colStats);
        List<ManifoldAxis> axes = ar.axes;
        List<String> fields = ar.fields;

        if (axes.isEmpty() || fields.isEmpty()) {
            LOG.info("[Manifold] Not enough axes or fields to build surface");
            return null;
        }

        Map<List<String>, GridPoint> grid = GridBuilder.buildGrid(rows, axes, fields);
        if (grid.size() < 4) {
            LOG.info("[Manifold] Grid too small (" + grid.size() + " points)");
            return null;
        }

        int handles = 0, crosscaps = 0;
        for (int i = 0; i < axes.size(); i++) {
            ManifoldAxis ax = axes.get(i);
            if (ax.isOrdered() && ax.getSize() >= 4) {
                int[] wr = ManifoldDiagnostics.checkBoundaryWrap(grid, axes, fields, i);
                boolean wraps = wr[0] == 1;
                int orientation = wr[1];
                ax.setWraps(wraps);
                ax.setWrapOrientation(orientation);
                if (wraps) {
                    if (orientation == 1) handles++;
                    else if (orientation == -1) crosscaps++;
                }
            }
        }

        GridBuilder.buildAdjacency(grid, axes);
        ManifoldDiagnostics.computeFieldCurvature(grid, fields);
        ManifoldDiagnostics.computeFormanRicci(grid);
        ManifoldDiagnostics.classifyMorsePoints(grid, fields);

        int[] betti = ManifoldDiagnostics.computeBettiNumbers(grid);

        Map<String, List<CriticalPoint>> persistencePairs = new LinkedHashMap<>();
        for (String f : fields.subList(0, Math.min(5, fields.size()))) {
            List<CriticalPoint> pairs = ManifoldDiagnostics.computePersistence(grid, f);
            if (!pairs.isEmpty()) {
                persistencePairs.put(f, pairs.subList(0, Math.min(20, pairs.size())));
            }
        }

        List<Map<String, Object>> interactions = ManifoldDiagnostics.computeInteractionCurvature(grid, fields);

        TopologyClassifier.TopologyResult topo = TopologyClassifier.classifyProductTopology(axes);
        Map<String, Object> faceStats = TopologyClassifier.computeFaceTopology2d(grid, axes);

        String classificationMode = "graph_heuristic";
        int faceCount = 0; double faceCoverage = 0, occupancy = 0;
        int faceVertexCount = 0, faceEdgeCount = 0, boundaryEdgeCount = 0, boundaryLoopCount = 0;
        Integer faceEulerChar = null; Double faceGenusEst = null; Integer faceCrosscapEst = null;
        boolean isManifold = false; int nonmanifoldEdges = 0; double confidence = 0;

        if (faceStats != null) {
            classificationMode = (String) faceStats.getOrDefault("mode", "graph_heuristic");
            confidence = ((Number) faceStats.getOrDefault("confidence", 0.0)).doubleValue();
            faceCount = ((Number) faceStats.getOrDefault("faces", 0)).intValue();
            faceCoverage = ((Number) faceStats.getOrDefault("face_coverage", 0.0)).doubleValue();
            occupancy = ((Number) faceStats.getOrDefault("occupancy", 0.0)).doubleValue();
            faceVertexCount = ((Number) faceStats.getOrDefault("face_vertex_count", 0)).intValue();
            faceEdgeCount = ((Number) faceStats.getOrDefault("face_edge_count", 0)).intValue();
            boundaryEdgeCount = ((Number) faceStats.getOrDefault("boundary_edges", 0)).intValue();
            boundaryLoopCount = ((Number) faceStats.getOrDefault("boundary_loops", 0)).intValue();
            faceEulerChar = faceStats.get("face_euler_characteristic") instanceof Number n ? n.intValue() : null;
            if (topo.orientable) {
                faceGenusEst = faceStats.get("face_genus_estimate_orientable") instanceof Number n ? n.doubleValue() : null;
            }
            isManifold = Boolean.TRUE.equals(faceStats.get("is_manifold"));
            nonmanifoldEdges = ((Number) faceStats.getOrDefault("nonmanifold_edges", 0)).intValue();
        }

        String surfaceName;
        if (faceCount == 0) surfaceName = "graph_only";
        else if (!isManifold) surfaceName = "nonmanifold_complex";
        else surfaceName = topo.surfaceName;

        int edgeCount = 0;
        for (GridPoint gp : grid.values()) edgeCount += gp.getNeighbors().size();
        edgeCount /= 2;

        Map<String, Object> surfaceConfidence = new LinkedHashMap<>();
        surfaceConfidence.put("has_faces", faceCount > 0);
        surfaceConfidence.put("is_edge_manifold", isManifold);
        surfaceConfidence.put("nonmanifold_edges", nonmanifoldEdges);
        surfaceConfidence.put("face_coverage", faceCoverage);
        surfaceConfidence.put("occupancy", occupancy);
        surfaceConfidence.put("classification_mode", classificationMode);
        surfaceConfidence.put("confidence", confidence);

        ManifoldSurface surface = new ManifoldSurface(
                axes, fields, grid, surfaceName, topo.genus, topo.orientable,
                topo.eulerCharacteristic, handles, crosscaps, grid.size(), edgeCount);
        surface.setBetti0(betti[0]);
        surface.setBetti1(betti[1]);
        surface.setPersistencePairs(persistencePairs);
        surface.setInteractions(interactions);
        surface.setClassificationMode(classificationMode);
        surface.setProductTopologyLabel(topo.surfaceName);
        surface.setSurfaceConfidenceObj(surfaceConfidence);
        surface.setFaceCount(faceCount);
        surface.setFaceCoverage(faceCoverage);
        surface.setOccupancy(occupancy);
        surface.setFaceVertexCount(faceVertexCount);
        surface.setFaceEdgeCount(faceEdgeCount);
        surface.setBoundaryEdgeCount(boundaryEdgeCount);
        surface.setBoundaryLoopCount(boundaryLoopCount);
        surface.setFaceEulerCharacteristic(faceEulerChar);
        surface.setFaceGenusEstimate(faceGenusEst);
        surface.setFaceCrosscapEstimate(faceCrosscapEst);
        surface.setManifold(isManifold);
        surface.setNonmanifoldEdges(nonmanifoldEdges);

        LOG.info(String.format("[Manifold] Built %s: %d axes, %d fields, %d vertices, %d edges",
                surfaceName, axes.size(), fields.size(), grid.size(), edgeCount));
        return surface;
    }
}
