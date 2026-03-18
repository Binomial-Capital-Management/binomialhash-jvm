package com.binomialtechnologies.binomialhash.manifold;

import java.util.*;

/**
 * Full manifold summary object returned by the builder.
 */
public class ManifoldSurface {

    private final List<ManifoldAxis> axes;
    private final List<String> fieldColumns;
    private final Map<List<String>, GridPoint> grid;
    private final String surfaceName;
    private final int genus;
    private final boolean orientable;
    private final int eulerCharacteristic;
    private final int handles;
    private final int crosscaps;
    private final int vertexCount;
    private final int edgeCount;

    private int betti0;
    private int betti1;
    private Map<String, List<CriticalPoint>> persistencePairs = new LinkedHashMap<>();
    private List<Map<String, Object>> interactions = new ArrayList<>();
    private String classificationMode = "graph_heuristic";
    private String productTopologyLabel = "";
    private Map<String, Object> surfaceConfidenceObj = new LinkedHashMap<>();
    private int faceCount;
    private double faceCoverage;
    private double occupancy;
    private int faceVertexCount;
    private int faceEdgeCount;
    private int boundaryEdgeCount;
    private int boundaryLoopCount;
    private Integer faceEulerCharacteristic;
    private Double faceGenusEstimate;
    private Integer faceCrosscapEstimate;
    private boolean isManifold;
    private int nonmanifoldEdges;

    public ManifoldSurface(
            List<ManifoldAxis> axes, List<String> fieldColumns,
            Map<List<String>, GridPoint> grid, String surfaceName,
            int genus, boolean orientable, int eulerCharacteristic,
            int handles, int crosscaps, int vertexCount, int edgeCount) {
        this.axes = axes;
        this.fieldColumns = fieldColumns;
        this.grid = grid;
        this.surfaceName = surfaceName;
        this.genus = genus;
        this.orientable = orientable;
        this.eulerCharacteristic = eulerCharacteristic;
        this.handles = handles;
        this.crosscaps = crosscaps;
        this.vertexCount = vertexCount;
        this.edgeCount = edgeCount;
    }

    // --- getters ---
    public List<ManifoldAxis> getAxes() { return axes; }
    public List<String> getFieldColumns() { return fieldColumns; }
    public Map<List<String>, GridPoint> getGrid() { return grid; }
    public String getSurfaceName() { return surfaceName; }
    public int getGenus() { return genus; }
    public boolean isOrientable() { return orientable; }
    public int getEulerCharacteristic() { return eulerCharacteristic; }
    public int getHandles() { return handles; }
    public int getCrosscaps() { return crosscaps; }
    public int getVertexCount() { return vertexCount; }
    public int getEdgeCount() { return edgeCount; }

    public int getBetti0() { return betti0; }
    public void setBetti0(int betti0) { this.betti0 = betti0; }
    public int getBetti1() { return betti1; }
    public void setBetti1(int betti1) { this.betti1 = betti1; }
    public Map<String, List<CriticalPoint>> getPersistencePairs() { return persistencePairs; }
    public void setPersistencePairs(Map<String, List<CriticalPoint>> p) { this.persistencePairs = p; }
    public List<Map<String, Object>> getInteractions() { return interactions; }
    public void setInteractions(List<Map<String, Object>> i) { this.interactions = i; }
    public String getClassificationMode() { return classificationMode; }
    public void setClassificationMode(String m) { this.classificationMode = m; }
    public String getProductTopologyLabel() { return productTopologyLabel; }
    public void setProductTopologyLabel(String l) { this.productTopologyLabel = l; }
    public Map<String, Object> getSurfaceConfidenceObj() { return surfaceConfidenceObj; }
    public void setSurfaceConfidenceObj(Map<String, Object> o) { this.surfaceConfidenceObj = o; }
    public int getFaceCount() { return faceCount; }
    public void setFaceCount(int f) { this.faceCount = f; }
    public double getFaceCoverage() { return faceCoverage; }
    public void setFaceCoverage(double c) { this.faceCoverage = c; }
    public double getOccupancy() { return occupancy; }
    public void setOccupancy(double o) { this.occupancy = o; }
    public int getFaceVertexCount() { return faceVertexCount; }
    public void setFaceVertexCount(int v) { this.faceVertexCount = v; }
    public int getFaceEdgeCount() { return faceEdgeCount; }
    public void setFaceEdgeCount(int e) { this.faceEdgeCount = e; }
    public int getBoundaryEdgeCount() { return boundaryEdgeCount; }
    public void setBoundaryEdgeCount(int b) { this.boundaryEdgeCount = b; }
    public int getBoundaryLoopCount() { return boundaryLoopCount; }
    public void setBoundaryLoopCount(int l) { this.boundaryLoopCount = l; }
    public Integer getFaceEulerCharacteristic() { return faceEulerCharacteristic; }
    public void setFaceEulerCharacteristic(Integer e) { this.faceEulerCharacteristic = e; }
    public Double getFaceGenusEstimate() { return faceGenusEstimate; }
    public void setFaceGenusEstimate(Double g) { this.faceGenusEstimate = g; }
    public Integer getFaceCrosscapEstimate() { return faceCrosscapEstimate; }
    public void setFaceCrosscapEstimate(Integer c) { this.faceCrosscapEstimate = c; }
    public boolean isManifold() { return isManifold; }
    public void setManifold(boolean m) { this.isManifold = m; }
    public int getNonmanifoldEdges() { return nonmanifoldEdges; }
    public void setNonmanifoldEdges(int n) { this.nonmanifoldEdges = n; }

    /** Serialize a compact manifold summary. */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> axesList = new ArrayList<>();
        for (ManifoldAxis a : axes) {
            axesList.add(a.toMap());
        }
        result.put("axes", axesList);
        result.put("fields", fieldColumns);
        result.put("vertices", vertexCount);
        result.put("edges", edgeCount);
        result.put("surface", surfaceName);
        result.put("genus", genus);
        result.put("orientable", orientable);
        result.put("euler_characteristic", eulerCharacteristic);
        result.put("betti_0", betti0);
        result.put("betti_1", betti1);
        result.put("handles", handles);
        result.put("crosscaps", crosscaps);

        Map<String, Object> faceComplex = new LinkedHashMap<>();
        faceComplex.put("vertices", faceVertexCount);
        faceComplex.put("edges", faceEdgeCount);
        faceComplex.put("faces", faceCount);
        faceComplex.put("boundary_edges", boundaryEdgeCount);
        faceComplex.put("boundary_loops", boundaryLoopCount);
        faceComplex.put("face_coverage", faceCoverage);
        faceComplex.put("occupancy", occupancy);
        faceComplex.put("euler_characteristic", faceEulerCharacteristic);
        faceComplex.put("genus_estimate", faceGenusEstimate);
        faceComplex.put("crosscap_estimate", faceCrosscapEstimate);
        faceComplex.put("is_manifold", isManifold);
        faceComplex.put("nonmanifold_edges", nonmanifoldEdges);
        result.put("face_complex", faceComplex);

        result.put("classification_mode", classificationMode);
        result.put("product_topology_label", productTopologyLabel);
        result.put("surface_confidence", surfaceConfidenceObj);
        result.put("interactions", interactions);

        return result;
    }
}
