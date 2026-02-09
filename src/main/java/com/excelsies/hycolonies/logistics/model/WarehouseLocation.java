package com.excelsies.hycolonies.logistics.model;

import com.hypixel.hytale.math.vector.Vector3i;

import java.util.UUID;

/**
 * Represents a warehouse location identified by world ID, colony ID, and block position.
 * The worldId prevents cross-world warehouse collisions where two warehouses at the same
 * coordinates in different worlds would incorrectly be considered the same.
 */
public record WarehouseLocation(
    String worldId,
    UUID colonyId,
    Vector3i position
) {
    /**
     * Creates a WarehouseLocation with default worldId for backwards compatibility.
     * @deprecated Use the full constructor with worldId instead.
     */
    @Deprecated
    public WarehouseLocation(UUID colonyId, Vector3i position) {
        this("default", colonyId, position);
    }

    /**
     * Calculates the squared distance to another location.
     * Returns Double.MAX_VALUE if locations are in different worlds.
     */
    public double distanceSquaredTo(WarehouseLocation other) {
        if (!worldId.equals(other.worldId)) {
            return Double.MAX_VALUE;
        }
        int dx = position.getX() - other.position.getX();
        int dy = position.getY() - other.position.getY();
        int dz = position.getZ() - other.position.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Calculates the squared distance to a position.
     * Note: This method does not check world since Vector3i has no world context.
     * Caller must ensure positions are in the same world.
     */
    public double distanceSquaredTo(Vector3i other) {
        int dx = position.getX() - other.getX();
        int dy = position.getY() - other.getY();
        int dz = position.getZ() - other.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Returns true if this location is in the same world as another.
     */
    public boolean isSameWorld(WarehouseLocation other) {
        return worldId.equals(other.worldId);
    }

    /**
     * Returns true if this location is in the specified world.
     */
    public boolean isInWorld(String world) {
        return worldId.equals(world);
    }
}
