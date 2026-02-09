package com.excelsies.hycolonies.logistics.model;

import com.hypixel.hytale.math.vector.Vector3i;

import java.util.UUID;

/**
 * Represents a warehouse location identified by colony ID and block position.
 */
public record WarehouseLocation(
    UUID colonyId,
    Vector3i position
) {
    /**
     * Calculates the squared distance to another location.
     * Only meaningful for locations in the same world.
     */
    public double distanceSquaredTo(WarehouseLocation other) {
        int dx = position.getX() - other.position.getX();
        int dy = position.getY() - other.position.getY();
        int dz = position.getZ() - other.position.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Calculates the squared distance to a position.
     */
    public double distanceSquaredTo(Vector3i other) {
        int dx = position.getX() - other.getX();
        int dy = position.getY() - other.getY();
        int dz = position.getZ() - other.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
