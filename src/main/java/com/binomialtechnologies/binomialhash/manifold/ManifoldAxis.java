package com.binomialtechnologies.binomialhash.manifold;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * One inferred parameter-space axis in the manifold view.
 */
public class ManifoldAxis {

    private final String column;
    private final List<Object> values;
    private final boolean ordered;
    private final String axisType;
    private final int size;
    private boolean wraps;
    private int wrapOrientation;

    public ManifoldAxis(String column, List<Object> values, boolean ordered,
                        String axisType, int size, boolean wraps, int wrapOrientation) {
        this.column = column;
        this.values = values;
        this.ordered = ordered;
        this.axisType = axisType;
        this.size = size;
        this.wraps = wraps;
        this.wrapOrientation = wrapOrientation;
    }

    public ManifoldAxis(String column, List<Object> values, boolean ordered,
                        String axisType, int size) {
        this(column, values, ordered, axisType, size, false, 0);
    }

    public String getColumn() { return column; }
    public List<Object> getValues() { return values; }
    public boolean isOrdered() { return ordered; }
    public String getAxisType() { return axisType; }
    public int getSize() { return size; }
    public boolean isWraps() { return wraps; }
    public void setWraps(boolean wraps) { this.wraps = wraps; }
    public int getWrapOrientation() { return wrapOrientation; }
    public void setWrapOrientation(int wrapOrientation) { this.wrapOrientation = wrapOrientation; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("column", column);
        m.put("ordered", ordered);
        m.put("axis_type", axisType);
        m.put("size", size);
        m.put("wraps", wraps);
        m.put("wrap_orientation", wrapOrientation);
        return m;
    }
}
