package com.excelsies.hycolonies.logistics.service;

import com.excelsies.hycolonies.logistics.model.ItemEntry;
import com.excelsies.hycolonies.logistics.model.SupplySnapshot;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe service that maintains a cached view of all warehouse inventories.
 * Uses event-driven cache invalidation for efficiency.
 *
 * The cache is used to create immutable snapshots for the async logistics solver,
 * allowing O(1) read operations instead of scanning physical inventories each tick.
 */
public class InventoryCacheService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Cached inventory data for a single warehouse.
     */
    public record CachedInventory(List<ItemEntry> items, long lastUpdated) {
        public CachedInventory(List<ItemEntry> items) {
            this(List.copyOf(items), System.currentTimeMillis());
        }

        public static CachedInventory empty() {
            return new CachedInventory(List.of(), System.currentTimeMillis());
        }
    }

    // Colony UUID -> (Warehouse Position -> Cached Inventory Contents)
    private final Map<UUID, Map<Vector3i, CachedInventory>> colonyWarehouseCache;

    // ReadWriteLock for thread-safe access
    private final ReadWriteLock cacheLock;

    public InventoryCacheService() {
        this.colonyWarehouseCache = new ConcurrentHashMap<>();
        this.cacheLock = new ReentrantReadWriteLock();
    }

    /**
     * Registers a warehouse with the cache, initializing it with empty contents.
     *
     * @param colonyId The colony UUID
     * @param position The warehouse block position
     */
    public void registerWarehouse(UUID colonyId, Vector3i position) {
        cacheLock.writeLock().lock();
        try {
            colonyWarehouseCache
                    .computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>())
                    .put(position, CachedInventory.empty());
            LOGGER.atInfo().log("Registered warehouse cache for colony %s at %s",
                    colonyId.toString().substring(0, 8), position);
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Unregisters a warehouse from the cache.
     *
     * @param colonyId The colony UUID
     * @param position The warehouse block position
     */
    public void unregisterWarehouse(UUID colonyId, Vector3i position) {
        cacheLock.writeLock().lock();
        try {
            Map<Vector3i, CachedInventory> warehouses = colonyWarehouseCache.get(colonyId);
            if (warehouses != null) {
                warehouses.remove(position);
                if (warehouses.isEmpty()) {
                    colonyWarehouseCache.remove(colonyId);
                }
            }
            LOGGER.atInfo().log("Unregistered warehouse cache for colony %s at %s",
                    colonyId.toString().substring(0, 8), position);
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Unregisters all warehouses for a colony.
     *
     * @param colonyId The colony UUID
     */
    public void unregisterAllForColony(UUID colonyId) {
        cacheLock.writeLock().lock();
        try {
            colonyWarehouseCache.remove(colonyId);
            LOGGER.atInfo().log("Unregistered all warehouse caches for colony %s",
                    colonyId.toString().substring(0, 8));
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Updates the cache for a specific warehouse with new contents.
     * Called by event handlers when inventory changes are detected.
     *
     * @param colonyId    The colony UUID
     * @param position    The warehouse block position
     * @param newContents The updated inventory contents
     */
    public void invalidateAndUpdate(UUID colonyId, Vector3i position, List<ItemEntry> newContents) {
        cacheLock.writeLock().lock();
        try {
            Map<Vector3i, CachedInventory> warehouses =
                    colonyWarehouseCache.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>());

            warehouses.put(position, new CachedInventory(newContents));

            LOGGER.atFine().log("Updated cache for warehouse at %s (colony %s) - %d item types",
                    position, colonyId.toString().substring(0, 8), newContents.size());
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Creates an immutable snapshot of all warehouse inventories.
     * This is O(N) where N = total warehouse count, but fast (just reference copies).
     *
     * @return Immutable supply snapshot for the async solver
     */
    public SupplySnapshot createSnapshot() {
        cacheLock.readLock().lock();
        try {
            Map<UUID, Map<Vector3i, List<ItemEntry>>> snapshot = new HashMap<>();

            for (var colonyEntry : colonyWarehouseCache.entrySet()) {
                Map<Vector3i, List<ItemEntry>> warehousesCopy = new HashMap<>();
                for (var warehouseEntry : colonyEntry.getValue().entrySet()) {
                    // Copy item list (ItemEntry records are immutable)
                    warehousesCopy.put(
                            warehouseEntry.getKey(),
                            new ArrayList<>(warehouseEntry.getValue().items())
                    );
                }
                snapshot.put(colonyEntry.getKey(), warehousesCopy);
            }

            return new SupplySnapshot(snapshot);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Gets the cached contents of a specific warehouse.
     *
     * @param colonyId The colony UUID
     * @param position The warehouse block position
     * @return List of items, or empty list if not cached
     */
    public List<ItemEntry> getWarehouseContents(UUID colonyId, Vector3i position) {
        cacheLock.readLock().lock();
        try {
            Map<Vector3i, CachedInventory> warehouses = colonyWarehouseCache.get(colonyId);
            if (warehouses == null) return List.of();

            CachedInventory cached = warehouses.get(position);
            return cached != null ? cached.items() : List.of();
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Gets the total quantity of an item across all warehouses in a colony.
     *
     * @param colonyId The colony UUID
     * @param itemId   The item identifier
     * @return Total quantity available
     */
    public int getTotalItemQuantity(UUID colonyId, String itemId) {
        cacheLock.readLock().lock();
        try {
            Map<Vector3i, CachedInventory> warehouses = colonyWarehouseCache.get(colonyId);
            if (warehouses == null) return 0;

            int total = 0;
            for (CachedInventory cached : warehouses.values()) {
                for (ItemEntry item : cached.items()) {
                    if (item.itemId().equals(itemId)) {
                        total += item.quantity();
                    }
                }
            }
            return total;
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Finds all warehouses in a colony that contain a specific item.
     *
     * @param colonyId The colony UUID
     * @param itemId   The item identifier
     * @return List of warehouse positions with the item
     */
    public List<Vector3i> findWarehousesWithItem(UUID colonyId, String itemId) {
        cacheLock.readLock().lock();
        try {
            Map<Vector3i, CachedInventory> warehouses = colonyWarehouseCache.get(colonyId);
            if (warehouses == null) return List.of();

            List<Vector3i> result = new ArrayList<>();
            for (var entry : warehouses.entrySet()) {
                boolean hasItem = entry.getValue().items().stream()
                        .anyMatch(item -> item.itemId().equals(itemId) && item.quantity() > 0);
                if (hasItem) {
                    result.add(entry.getKey());
                }
            }
            return result;
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Gets the number of cached warehouses for a colony.
     *
     * @param colonyId The colony UUID
     * @return Number of warehouses in cache
     */
    public int getWarehouseCount(UUID colonyId) {
        cacheLock.readLock().lock();
        try {
            Map<Vector3i, CachedInventory> warehouses = colonyWarehouseCache.get(colonyId);
            return warehouses != null ? warehouses.size() : 0;
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Gets the total number of cached warehouses across all colonies.
     *
     * @return Total warehouse count
     */
    public int getTotalWarehouseCount() {
        cacheLock.readLock().lock();
        try {
            int total = 0;
            for (Map<Vector3i, CachedInventory> warehouses : colonyWarehouseCache.values()) {
                total += warehouses.size();
            }
            return total;
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Clears all cached data.
     */
    public void clear() {
        cacheLock.writeLock().lock();
        try {
            colonyWarehouseCache.clear();
            LOGGER.atInfo().log("Cleared all warehouse caches");
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Returns true if a warehouse is registered in the cache.
     *
     * @param colonyId The colony UUID
     * @param position The warehouse block position
     * @return true if the warehouse is cached
     */
    public boolean isWarehouseCached(UUID colonyId, Vector3i position) {
        cacheLock.readLock().lock();
        try {
            Map<Vector3i, CachedInventory> warehouses = colonyWarehouseCache.get(colonyId);
            return warehouses != null && warehouses.containsKey(position);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Gets the age of the cache for a specific warehouse.
     *
     * @param colonyId The colony UUID
     * @param position The warehouse block position
     * @return Age in milliseconds, or -1 if not cached
     */
    public long getCacheAge(UUID colonyId, Vector3i position) {
        cacheLock.readLock().lock();
        try {
            Map<Vector3i, CachedInventory> warehouses = colonyWarehouseCache.get(colonyId);
            if (warehouses == null) return -1;

            CachedInventory cached = warehouses.get(position);
            if (cached == null) return -1;

            return System.currentTimeMillis() - cached.lastUpdated();
        } finally {
            cacheLock.readLock().unlock();
        }
    }
}
