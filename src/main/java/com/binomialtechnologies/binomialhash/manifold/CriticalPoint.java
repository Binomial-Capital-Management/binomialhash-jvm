package com.binomialtechnologies.binomialhash.manifold;

import java.util.List;

/**
 * A Morse-like critical point on the grid.
 */
public class CriticalPoint {

    private final int vertexIndex;
    private final List<String> coord;
    private final String morseType;
    private final String field;
    private final double value;
    private double persistence;

    public CriticalPoint(int vertexIndex, List<String> coord, String morseType,
                         String field, double value, double persistence) {
        this.vertexIndex = vertexIndex;
        this.coord = coord;
        this.morseType = morseType;
        this.field = field;
        this.value = value;
        this.persistence = persistence;
    }

    public CriticalPoint(int vertexIndex, List<String> coord, String morseType,
                         String field, double value) {
        this(vertexIndex, coord, morseType, field, value, 0.0);
    }

    public int getVertexIndex() { return vertexIndex; }
    public List<String> getCoord() { return coord; }
    public String getMorseType() { return morseType; }
    public String getField() { return field; }
    public double getValue() { return value; }
    public double getPersistence() { return persistence; }
    public void setPersistence(double persistence) { this.persistence = persistence; }
}
