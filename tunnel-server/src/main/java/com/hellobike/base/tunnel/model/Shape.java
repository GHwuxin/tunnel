package com.hellobike.base.tunnel.model;

/**
 * @author yang.song
 */
public class Shape {
    String type;
    Double[][][] coordinates;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Double[][][] getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(Double[][][] coordinates) {
        this.coordinates = coordinates;
    }
}
