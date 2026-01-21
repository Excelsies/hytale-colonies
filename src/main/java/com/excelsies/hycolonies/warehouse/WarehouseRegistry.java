package com.excelsies.hycolonies.warehouse;

import com.hypixel.hytale.math.vector.Vector3i;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry tracking which blocks are registered as colony warehouses.
 * Provides fast lookup from position to colony and vice versa.
 */
public class WarehouseRegistry {

    // Position (as string key) -> Colony UUID
    private final Map<String, UUID> warehouseToColony;

    // Colony UUID -> Set of warehouse positions
    private final Map<UUID, Set<Vector3i>> colonyWarehouses;

    public WarehouseRegistry() {
        this.warehouseToColony = new ConcurrentHashMap<>();
        this.colonyWarehouses = new ConcurrentHashMap<>();
    }

    /**
     * Creates a string key from a position for map lookup.
     */
    private String positionKey(Vector3i position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    /**
     * Registers a warehouse position for a colony.
     *
     * @param colonyId The colony UUID
     * @param position The block position
     */
    public void registerWarehouse(UUID colonyId, Vector3i position) {
        String key = positionKey(position);
        warehouseToColony.put(key, colonyId);
        colonyWarehouses
                .computeIfAbsent(colonyId, k -> ConcurrentHashMap.newKeySet())
                .add(position);
    }

    /**
     * Unregisters a warehouse by its position.
     *
     * @param position The block position
     */
    public void unregisterWarehouse(Vector3i position) {
        String key = positionKey(position);
        UUID colonyId = warehouseToColony.remove(key);
        if (colonyId != null) {
            Set<Vector3i> warehouses = colonyWarehouses.get(colonyId);
            if (warehouses != null) {
                warehouses.remove(position);
                if (warehouses.isEmpty()) {
                    colonyWarehouses.remove(colonyId);
                }
            }
        }
    }

    /**
     * Unregisters all warehouses for a colony.
     *
     * @param colonyId The colony UUID
     */
    public void unregisterAllForColony(UUID colonyId) {
        Set<Vector3i> warehouses = colonyWarehouses.remove(colonyId);
        if (warehouses != null) {
            for (Vector3i position : warehouses) {
                warehouseToColony.remove(positionKey(position));
            }
        }
    }

    /**
     * Gets the colony that owns a warehouse at a given position.
     *
     * @param position The block position
     * @return The colony UUID, or null if not a registered warehouse
     */
    public UUID getColonyForWarehouse(Vector3i position) {
        return warehouseToColony.get(positionKey(position));
    }

    /**
     * Gets all warehouse positions for a colony.
     *
     * @param colonyId The colony UUID
     * @return Unmodifiable set of warehouse positions (empty if none)
     */
    public Set<Vector3i> getWarehousesForColony(UUID colonyId) {
        Set<Vector3i> warehouses = colonyWarehouses.get(colonyId);
        if (warehouses == null) {
            return Set.of();
        }
        return Collections.unmodifiableSet(warehouses);
    }

    /**
     * Returns true if a position is a registered warehouse.
     *
     * @param position The block position
     * @return true if registered as a warehouse
     */
    public boolean isWarehouse(Vector3i position) {
        return warehouseToColony.containsKey(positionKey(position));
    }

    /**
     * Returns true if a position is a warehouse for the specified colony.
     *
     * @param colonyId The colony UUID
     * @param position The block position
     * @return true if the position is a warehouse for this colony
     */
    public boolean isWarehouseForColony(UUID colonyId, Vector3i position) {
        UUID owner = warehouseToColony.get(positionKey(position));
        return colonyId.equals(owner);
    }

    /**
     * Gets all registered warehouses grouped by colony.
     *
     * @return Unmodifiable map of colony UUID to warehouse positions
     */
    public Map<UUID, Set<Vector3i>> getAllWarehouses() {
        return Collections.unmodifiableMap(colonyWarehouses);
    }

    /**
     * Gets the total number of registered warehouses.
     *
     * @return Total warehouse count
     */
    public int getTotalWarehouseCount() {
        return warehouseToColony.size();
    }

    /**
     * Gets the number of warehouses for a specific colony.
     *
     * @param colonyId The colony UUID
     * @return Warehouse count for the colony
     */
    public int getWarehouseCountForColony(UUID colonyId) {
        Set<Vector3i> warehouses = colonyWarehouses.get(colonyId);
        return warehouses != null ? warehouses.size() : 0;
    }

    /**
     * Clears all warehouse registrations.
     */
    public void clear() {
        warehouseToColony.clear();
        colonyWarehouses.clear();
    }
}
