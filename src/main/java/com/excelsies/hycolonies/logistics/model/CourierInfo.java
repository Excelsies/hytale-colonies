package com.excelsies.hycolonies.logistics.model;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;

import java.util.UUID;

/**
 * Lightweight info about a courier for the logistics solver.
 * Contains only the data needed to make assignment decisions.
 */
public record CourierInfo(
    UUID citizenId,
    UUID colonyId,
    Vector3d position,
    int carryCapacity
) {
    /**
     * Default carry capacity for couriers.
     */
    public static final int DEFAULT_CAPACITY = 64;

    /**
     * Creates courier info with default capacity.
     */
    public CourierInfo(UUID citizenId, UUID colonyId, Vector3d position) {
        this(citizenId, colonyId, position, DEFAULT_CAPACITY);
    }

    /**
     * Calculates squared distance to a warehouse location.
     */
    public double distanceSquaredTo(WarehouseLocation warehouse) {
        Vector3i warehousePos = warehouse.position();
        double dx = position.getX() - warehousePos.getX();
        double dy = position.getY() - warehousePos.getY();
        double dz = position.getZ() - warehousePos.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Calculates squared distance to a position.
     */
    public double distanceSquaredTo(Vector3d other) {
        double dx = position.getX() - other.getX();
        double dy = position.getY() - other.getY();
        double dz = position.getZ() - other.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Returns true if this courier belongs to the specified colony.
     */
    public boolean belongsToColony(UUID colony) {
        return colonyId.equals(colony);
    }
}
