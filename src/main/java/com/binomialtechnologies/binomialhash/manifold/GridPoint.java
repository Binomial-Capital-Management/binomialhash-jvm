package com.binomialtechnologies.binomialhash.manifold;

import java.util.*;

/**
 * A vertex in the discrete manifold grid.
 */
public class GridPoint {

    private final int index;
    private final List<String> axisCoords;
    private final Map<String, Double> fieldValues;
    private double curvature;
    private double formanRicci;
    private int density;
    private final List<Integer> neighbors;
    private final Map<String, String> morseType;

    public GridPoint(int index, List<String> axisCoords, Map<String, Double> fieldValues) {
        this.index = index;
        this.axisCoords = axisCoords;
        this.fieldValues = fieldValues;
        this.curvature = 0.0;
        this.formanRicci = 0.0;
        this.density = 0;
        this.neighbors = new ArrayList<>();
        this.morseType = new LinkedHashMap<>();
    }

    public int getIndex() { return index; }
    public List<String> getAxisCoords() { return axisCoords; }
    public Map<String, Double> getFieldValues() { return fieldValues; }
    public double getCurvature() { return curvature; }
    public void setCurvature(double curvature) { this.curvature = curvature; }
    public double getFormanRicci() { return formanRicci; }
    public void setFormanRicci(double formanRicci) { this.formanRicci = formanRicci; }
    public int getDensity() { return density; }
    public void setDensity(int density) { this.density = density; }
    public List<Integer> getNeighbors() { return neighbors; }
    public Map<String, String> getMorseType() { return morseType; }
}
