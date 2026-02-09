package com.excelsies.hycolonies.logistics.model;

import com.hypixel.hytale.math.vector.Vector3i;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable snapshot of all warehouse inventories at a point in time.
 * Used by the async LogisticsSolver to avoid race conditions with the main thread.
 */
public record SupplySnapshot(
    Map<UUID, Map<Vector3i, List<ItemEntry>>> warehouseContents,
    long snapshotTime
) {
    /**
     * Creates a snapshot with the current timestamp.
     */
    public SupplySnapshot(Map<UUID, Map<Vector3i, List<ItemEntry>>> warehouseContents) {
        this(deepCopy(warehouseContents), System.currentTimeMillis());
    }

    /**
     * Creates an empty snapshot.
     */
    public static SupplySnapshot empty() {
        return new SupplySnapshot(Map.of(), System.currentTimeMillis());
    }

    /**
     * Creates a deep copy of the warehouse contents map for immutability.
     */
    private static Map<UUID, Map<Vector3i, List<ItemEntry>>> deepCopy(
            Map<UUID, Map<Vector3i, List<ItemEntry>>> original) {
        Map<UUID, Map<Vector3i, List<ItemEntry>>> copy = new HashMap<>();
        for (var colonyEntry : original.entrySet()) {
            Map<Vector3i, List<ItemEntry>> warehousesCopy = new HashMap<>();
            for (var warehouseEntry : colonyEntry.getValue().entrySet()) {
                warehousesCopy.put(warehouseEntry.getKey(), List.copyOf(warehouseEntry.getValue()));
            }
            copy.put(colonyEntry.getKey(), Collections.unmodifiableMap(warehousesCopy));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Converts the snapshot to a mutable allocation map for the solver.
     * Returns: Map<WarehouseLocation, Map<ItemId, Quantity>>
     */
    public Map<WarehouseLocation, Map<String, Integer>> toMutableAllocationMap() {
        Map<WarehouseLocation, Map<String, Integer>> result = new HashMap<>();

        for (var colonyEntry : warehouseContents.entrySet()) {
            UUID colonyId = colonyEntry.getKey();
            for (var warehouseEntry : colonyEntry.getValue().entrySet()) {
                WarehouseLocation loc = new WarehouseLocation(colonyId, warehouseEntry.getKey());
                Map<String, Integer> itemCounts = new HashMap<>();

                for (ItemEntry item : warehouseEntry.getValue()) {
                    itemCounts.merge(item.itemId(), item.quantity(), Integer::sum);
                }

                result.put(loc, itemCounts);
            }
        }

        return result;
    }

    /**
     * Gets the total quantity of an item across all warehouses in a colony.
     */
    public int getTotalQuantity(UUID colonyId, String itemId) {
        Map<Vector3i, List<ItemEntry>> warehouses = warehouseContents.get(colonyId);
        if (warehouses == null) return 0;

        int total = 0;
        for (List<ItemEntry> items : warehouses.values()) {
            for (ItemEntry item : items) {
                if (item.itemId().equals(itemId)) {
                    total += item.quantity();
                }
            }
        }
        return total;
    }

    /**
     * Gets all warehouses in a colony that contain a specific item.
     */
    public List<WarehouseLocation> findWarehousesWithItem(UUID colonyId, String itemId) {
        Map<Vector3i, List<ItemEntry>> warehouses = warehouseContents.get(colonyId);
        if (warehouses == null) return List.of();

        return warehouses.entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .anyMatch(item -> item.itemId().equals(itemId) && item.quantity() > 0))
                .map(entry -> new WarehouseLocation(colonyId, entry.getKey()))
                .toList();
    }

    /**
     * Returns the age of this snapshot in milliseconds.
     */
    public long getAgeMs() {
        return System.currentTimeMillis() - snapshotTime;
    }
}
