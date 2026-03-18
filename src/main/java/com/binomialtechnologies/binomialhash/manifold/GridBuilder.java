package com.binomialtechnologies.binomialhash.manifold;

import java.util.*;

/**
 * Grid construction and adjacency building for the discrete manifold mesh.
 */
public final class GridBuilder {

    private GridBuilder() {}

    /**
     * Build the discrete mesh: map each row to a grid point defined by axis coordinates.
     */
    public static Map<List<String>, GridPoint> buildGrid(
            List<Map<String, Object>> rows,
            List<ManifoldAxis> axes,
            List<String> fields) {

        Map<List<String>, Map<String, List<Double>>> accumulator = new LinkedHashMap<>();
        Map<List<String>, Integer> density = new LinkedHashMap<>();
        int idxCounter = 0;

        for (Map<String, Object> row : rows) {
            List<String> coordParts = new ArrayList<>();
            boolean valid = true;
            for (ManifoldAxis ax : axes) {
                Object rv = row.get(ax.getColumn());
                if (rv == null) { valid = false; break; }
                coordParts.add(String.valueOf(rv));
            }
            if (!valid) continue;

            if (!accumulator.containsKey(coordParts)) {
                Map<String, List<Double>> fieldMap = new LinkedHashMap<>();
                for (String f : fields) fieldMap.put(f, new ArrayList<>());
                accumulator.put(coordParts, fieldMap);
                density.put(coordParts, 0);
            }

            density.merge(coordParts, 1, Integer::sum);
            for (String f : fields) {
                Object v = row.get(f);
                if (v != null) {
                    try {
                        accumulator.get(coordParts).get(f).add(Double.parseDouble(v.toString()));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        Map<List<String>, GridPoint> grid = new LinkedHashMap<>();
        for (Map.Entry<List<String>, Map<String, List<Double>>> entry : accumulator.entrySet()) {
            List<String> coord = entry.getKey();
            Map<String, Double> avgFields = new LinkedHashMap<>();
            for (Map.Entry<String, List<Double>> fe : entry.getValue().entrySet()) {
                List<Double> vals = fe.getValue();
                if (!vals.isEmpty()) {
                    avgFields.put(fe.getKey(), vals.stream().mapToDouble(Double::doubleValue).sum() / vals.size());
                }
            }
            GridPoint gp = new GridPoint(idxCounter++, coord, avgFields);
            gp.setDensity(density.get(coord));
            grid.put(coord, gp);
        }
        return grid;
    }

    /**
     * Connect neighboring grid points along each axis.
     */
    public static void buildAdjacency(Map<List<String>, GridPoint> grid, List<ManifoldAxis> axes) {
        List<List<String>> coordList = new ArrayList<>(grid.keySet());
        Map<List<String>, Integer> coordToIdx = new LinkedHashMap<>();
        for (List<String> c : coordList) coordToIdx.put(c, grid.get(c).getIndex());

        for (List<String> coord : coordList) {
            GridPoint gp = grid.get(coord);
            for (int axisI = 0; axisI < axes.size(); axisI++) {
                ManifoldAxis ax = axes.get(axisI);
                String currentVal = coord.get(axisI);

                if (!ax.isOrdered()) {
                    for (List<String> other : coordList) {
                        if (other.equals(coord)) continue;
                        boolean sameOnOther = true;
                        for (int j = 0; j < axes.size(); j++) {
                            if (j != axisI && !other.get(j).equals(coord.get(j))) {
                                sameOnOther = false;
                                break;
                            }
                        }
                        if (sameOnOther) {
                            Integer ni = coordToIdx.get(other);
                            if (ni != null && !gp.getNeighbors().contains(ni)) {
                                gp.getNeighbors().add(ni);
                            }
                        }
                    }
                } else {
                    List<String> valList = new ArrayList<>();
                    for (Object v : ax.getValues()) valList.add(String.valueOf(v));
                    int pos = valList.indexOf(currentVal);
                    if (pos < 0) continue;

                    for (int delta : new int[]{-1, 1}) {
                        int npos = pos + delta;
                        if (ax.isWraps()) {
                            npos = Math.floorMod(npos, valList.size());
                        }
                        if (npos >= 0 && npos < valList.size()) {
                            List<String> newCoord = new ArrayList<>(coord);
                            newCoord.set(axisI, valList.get(npos));
                            Integer ni = coordToIdx.get(newCoord);
                            if (ni != null && !gp.getNeighbors().contains(ni)) {
                                gp.getNeighbors().add(ni);
                            }
                        }
                    }
                }
            }
        }
    }
}
