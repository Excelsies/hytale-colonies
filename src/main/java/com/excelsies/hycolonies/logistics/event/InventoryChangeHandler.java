package com.excelsies.hycolonies.logistics.event;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.logistics.model.ItemEntry;
import com.excelsies.hycolonies.logistics.service.InventoryCacheService;
import com.excelsies.hycolonies.warehouse.WarehouseData;
import com.excelsies.hycolonies.warehouse.WarehouseRegistry;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer.ItemContainerChangeEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
// BlockState is deprecated but no replacement exists for accessing block inventories.
// ItemContainerBlockState still requires this API until Hytale provides an alternative.
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.math.util.ChunkUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles inventory change events for warehouse blocks.
 *
 * <p>Primary mode: Event-driven updates via ItemContainer.registerChangeEvent().
 * Fallback mode: Periodic scanning every 30 seconds (for resilience).
 *
 * <p>Event-driven updates provide immediate cache invalidation when chest
 * contents change, eliminating the 5-second stale data window.
 *
 * <p><b>API Deprecation Note:</b> This class uses the deprecated BlockState API
 * (WorldChunk.getState() and BlockState) to access block inventories. The
 * BlockState system is marked for removal by Hytale, but ItemContainerState/
 * ItemContainerBlockState are still the only way to access chest/container
 * inventories. This code should be updated when Hytale provides an alternative.
 *
 * @see com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState
 */
@SuppressWarnings({"deprecation", "removal"}) // BlockState API is deprecated but no replacement exists for block inventories
public class InventoryChangeHandler {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Scan interval in seconds for fallback periodic scanning.
     * Reduced frequency since event-driven updates handle most cases.
     */
    private static final long SCAN_INTERVAL_SECONDS = 30;

    /**
     * Scan interval when event mode is disabled (fallback only).
     */
    private static final long FALLBACK_ONLY_SCAN_INTERVAL_SECONDS = 5;

    private final InventoryCacheService cacheService;
    private final WarehouseRegistry warehouseRegistry;
    private final ColonyService colonyService;

    /**
     * Tracks registered container change listeners by warehouse position.
     * Used for cleanup when warehouses are unregistered.
     */
    private final Map<Vector3i, EventRegistration> containerListeners = new ConcurrentHashMap<>();

    /**
     * Tracks which colony each warehouse belongs to (for event callbacks).
     */
    private final Map<Vector3i, UUID> warehouseColonyMap = new ConcurrentHashMap<>();

    private ScheduledFuture<?> scanTask;
    private boolean eventModeEnabled = false;

    public InventoryChangeHandler(InventoryCacheService cacheService,
                                  WarehouseRegistry warehouseRegistry,
                                  ColonyService colonyService) {
        this.cacheService = cacheService;
        this.warehouseRegistry = warehouseRegistry;
        this.colonyService = colonyService;
    }

    /**
     * Starts the inventory change handler.
     * Enables event-driven mode and starts periodic scanning as backup.
     *
     * @param executor The scheduled executor service for fallback scanning
     */
    public void start(ScheduledExecutorService executor) {
        // Try to enable event-driven mode
        if (tryEnableEventMode()) {
            LOGGER.atInfo().log("InventoryChangeHandler: Event-driven mode enabled");
            LOGGER.atInfo().log("InventoryChangeHandler: Fallback scanning every %ds for resilience",
                    SCAN_INTERVAL_SECONDS);
            eventModeEnabled = true;
            // Still run periodic scans but less frequently as a fallback
            startPeriodicScan(executor, SCAN_INTERVAL_SECONDS);
        } else {
            // Fall back to frequent periodic scanning
            LOGGER.atInfo().log("InventoryChangeHandler: Event mode unavailable, using periodic scanning (every %ds)",
                    FALLBACK_ONLY_SCAN_INTERVAL_SECONDS);
            startPeriodicScan(executor, FALLBACK_ONLY_SCAN_INTERVAL_SECONDS);
        }
    }

    /**
     * Stops the inventory change handler and cleans up all listeners.
     */
    public void stop() {
        if (scanTask != null && !scanTask.isCancelled()) {
            scanTask.cancel(false);
            scanTask = null;
        }

        // Unregister all container listeners
        if (eventModeEnabled) {
            int unregistered = 0;
            for (Map.Entry<Vector3i, EventRegistration> entry : containerListeners.entrySet()) {
                try {
                    entry.getValue().unregister();
                    unregistered++;
                } catch (Exception e) {
                    LOGGER.atFine().log("Failed to unregister listener at %s: %s",
                            entry.getKey(), e.getMessage());
                }
            }
            containerListeners.clear();
            warehouseColonyMap.clear();
            LOGGER.atInfo().log("Unregistered %d container listeners", unregistered);
            eventModeEnabled = false;
        }

        LOGGER.atInfo().log("InventoryChangeHandler stopped");
    }

    /**
     * Attempts to enable event-driven mode.
     * Event-driven mode uses ItemContainer.registerChangeEvent() on individual containers.
     *
     * @return true if event mode is available (API exists)
     */
    private boolean tryEnableEventMode() {
        // The ItemContainer.registerChangeEvent() API is available in Hytale.
        // Individual container listeners are registered when warehouses are registered.
        try {
            // Verify the API is available by checking the method exists
            ItemContainer.class.getMethod("registerChangeEvent", java.util.function.Consumer.class);
            return true;
        } catch (NoSuchMethodException e) {
            LOGGER.atWarning().log("ItemContainer.registerChangeEvent() not available: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Starts the periodic warehouse scan task.
     *
     * @param executor The executor service
     * @param intervalSeconds The scan interval in seconds
     */
    private void startPeriodicScan(ScheduledExecutorService executor, long intervalSeconds) {
        scanTask = executor.scheduleAtFixedRate(
                this::scanAllWarehouses,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );
    }

    /**
     * Scans all registered warehouses and updates the cache.
     * Called periodically in fallback mode.
     */
    private void scanAllWarehouses() {
        try {
            int warehousesScanned = 0;
            int itemsFound = 0;

            for (ColonyData colony : colonyService.getAllColonies()) {
                UUID colonyId = colony.getColonyId();

                for (WarehouseData warehouse : colony.getWarehouses()) {
                    Vector3i position = warehouse.getPosition();

                    // Scan the warehouse inventory
                    List<ItemEntry> contents = scanWarehouseContents(colonyId, warehouse);

                    // Update the cache
                    cacheService.invalidateAndUpdate(colonyId, position, contents);

                    warehousesScanned++;
                    itemsFound += contents.size();
                }
            }

            if (warehousesScanned > 0) {
                LOGGER.atFine().log("Warehouse scan complete: %d warehouses, %d item stacks",
                        warehousesScanned, itemsFound);
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Error during warehouse scan");
        }
    }

    /**
     * Scans the contents of a specific warehouse block by reading the actual block inventory.
     *
     * @param colonyId The colony UUID that owns the warehouse
     * @param warehouse The warehouse to scan
     * @return List of items in the warehouse
     */
    private List<ItemEntry> scanWarehouseContents(UUID colonyId, WarehouseData warehouse) {
        List<ItemEntry> contents = new ArrayList<>();

        try {
            // Get the world
            World world = getWorld(warehouse.getWorldId());
            if (world == null) {
                LOGGER.atFine().log("Could not get world '%s' for warehouse scan", warehouse.getWorldId());
                return getFallbackContents(colonyId, warehouse);
            }

            Vector3i pos = warehouse.getPosition();

            // Get the chunk containing this position
            long chunkKey = ChunkUtil.indexChunkFromBlock(pos.getX(), pos.getZ());
            WorldChunk chunk = world.getChunkIfLoaded(chunkKey);

            if (chunk == null) {
                LOGGER.atFine().log("Chunk not loaded for warehouse at %s", pos);
                return getFallbackContents(colonyId, warehouse);
            }

            // Get block state at position (local coordinates within chunk)
            int localX = ChunkUtil.localCoordinate(pos.getX());
            int localY = pos.getY();
            int localZ = ChunkUtil.localCoordinate(pos.getZ());

            // Uses deprecated getState() - see class-level javadoc for rationale
            var blockState = chunk.getState(localX, localY, localZ);

            if (blockState instanceof ItemContainerBlockState containerState) {
                ItemContainer container = containerState.getItemContainer();
                if (container != null) {
                    // Lazy registration: if event mode is enabled and no listener exists, register one
                    if (eventModeEnabled && !containerListeners.containsKey(pos)) {
                        try {
                            warehouseColonyMap.put(pos, colonyId);
                            EventRegistration registration = container.registerChangeEvent(event -> {
                                onContainerChange(pos, event);
                            });
                            containerListeners.put(pos, registration);
                            LOGGER.atInfo().log("Lazy-registered event listener for warehouse at %s", pos);
                        } catch (Exception e) {
                            LOGGER.atFine().log("Failed to lazy-register listener at %s: %s", pos, e.getMessage());
                        }
                    }

                    // Use a map to aggregate quantities by item ID
                    Map<String, Integer> itemQuantities = new HashMap<>();

                    container.forEach((slot, itemStack) -> {
                        if (itemStack != null && !itemStack.isEmpty()) {
                            String itemId = itemStack.getItemId();
                            int quantity = itemStack.getQuantity();
                            itemQuantities.merge(itemId, quantity, Integer::sum);
                        }
                    });

                    // Convert to ItemEntry list
                    for (Map.Entry<String, Integer> entry : itemQuantities.entrySet()) {
                        contents.add(new ItemEntry(entry.getKey(), entry.getValue()));
                    }

                    LOGGER.atFine().log("Scanned warehouse at %s: %d item types, %d total items",
                            pos, contents.size(),
                            contents.stream().mapToInt(ItemEntry::quantity).sum());
                    return contents;
                }
            }

            // Block doesn't have an item container
            LOGGER.atFine().log("Block at %s is not an item container (state: %s)",
                    pos, blockState != null ? blockState.getClass().getSimpleName() : "null");
            return getFallbackContents(colonyId, warehouse);

        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Error scanning warehouse at %s", warehouse.getPosition());
            return getFallbackContents(colonyId, warehouse);
        }
    }

    /**
     * Gets the world by ID.
     */
    private World getWorld(String worldId) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return null;
            }
            return universe.getWorld(worldId);
        } catch (Exception e) {
            LOGGER.atFine().log("Error getting world '%s': %s", worldId, e.getMessage());
            return null;
        }
    }

    /**
     * Returns cached contents as fallback when direct scanning fails.
     */
    private List<ItemEntry> getFallbackContents(UUID colonyId, WarehouseData warehouse) {
        List<ItemEntry> cached = cacheService.getWarehouseContents(colonyId, warehouse.getPosition());
        if (cached != null && !cached.isEmpty()) {
            return new ArrayList<>(cached);
        }
        return new ArrayList<>();
    }

    /**
     * Manually triggers an update for a specific warehouse.
     * Can be called when we know an inventory has changed (e.g., via command).
     *
     * @param colonyId The colony UUID
     * @param position The warehouse position
     */
    public void triggerUpdate(UUID colonyId, Vector3i position) {
        ColonyData colony = colonyService.getColony(colonyId).orElse(null);
        if (colony == null) return;

        WarehouseData warehouse = colony.getWarehouses().stream()
                .filter(w -> w.getPosition().equals(position))
                .findFirst()
                .orElse(null);

        if (warehouse != null) {
            List<ItemEntry> contents = scanWarehouseContents(colonyId, warehouse);
            cacheService.invalidateAndUpdate(colonyId, position, contents);
            LOGGER.atFine().log("Manual warehouse update triggered at %s", position);
        }
    }

    // =====================
    // Event-Driven Container Listener Methods
    // =====================

    /**
     * Registers an event listener on a warehouse's ItemContainer.
     * Must be called from the world thread or will schedule on it.
     *
     * @param colonyId The colony UUID that owns this warehouse
     * @param position The warehouse block position
     * @param world    The world containing the warehouse
     */
    public void registerContainerListener(UUID colonyId, Vector3i position, World world) {
        if (!eventModeEnabled) {
            LOGGER.atFine().log("Event mode disabled, skipping listener registration for %s", position);
            return;
        }

        // Track the colony association
        warehouseColonyMap.put(position, colonyId);

        // Register on world thread
        world.execute(() -> {
            try {
                long chunkKey = ChunkUtil.indexChunkFromBlock(position.getX(), position.getZ());
                WorldChunk chunk = world.getChunkIfLoaded(chunkKey);

                if (chunk == null) {
                    LOGGER.atFine().log("Chunk not loaded for warehouse at %s, listener will register on next scan", position);
                    return;
                }

                int localX = ChunkUtil.localCoordinate(position.getX());
                int localY = position.getY();
                int localZ = ChunkUtil.localCoordinate(position.getZ());

                BlockState blockState = chunk.getState(localX, localY, localZ);

                if (blockState instanceof ItemContainerBlockState containerState) {
                    ItemContainer container = containerState.getItemContainer();
                    if (container != null) {
                        // Register the change listener
                        EventRegistration registration = container.registerChangeEvent(event -> {
                            onContainerChange(position, event);
                        });

                        containerListeners.put(position, registration);
                        LOGGER.atInfo().log("Registered event listener for warehouse at %s", position);

                        // Do an initial scan to populate cache
                        List<ItemEntry> contents = scanContainerContents(container);
                        cacheService.invalidateAndUpdate(colonyId, position, contents);
                    }
                } else {
                    LOGGER.atWarning().log("Block at %s is not an item container, cannot register listener", position);
                }
            } catch (Exception e) {
                LOGGER.atWarning().withCause(e).log("Failed to register container listener at %s", position);
            }
        });
    }

    /**
     * Unregisters the event listener for a warehouse's ItemContainer.
     *
     * @param position The warehouse block position
     */
    public void unregisterContainerListener(Vector3i position) {
        EventRegistration registration = containerListeners.remove(position);
        warehouseColonyMap.remove(position);

        if (registration != null) {
            try {
                registration.unregister();
                LOGGER.atInfo().log("Unregistered event listener for warehouse at %s", position);
            } catch (Exception e) {
                LOGGER.atFine().log("Failed to unregister listener at %s: %s", position, e.getMessage());
            }
        }
    }

    /**
     * Callback invoked when a warehouse container's contents change.
     *
     * @param position The warehouse position
     * @param event    The change event
     */
    private void onContainerChange(Vector3i position, ItemContainerChangeEvent event) {
        UUID colonyId = warehouseColonyMap.get(position);
        if (colonyId == null) {
            LOGGER.atFine().log("Container change at %s but no colony mapping found", position);
            return;
        }

        try {
            // Scan the updated container contents
            List<ItemEntry> contents = scanContainerContents(event.container());
            cacheService.invalidateAndUpdate(colonyId, position, contents);
            LOGGER.atFine().log("Event-driven update: warehouse at %s, %d item types",
                    position, contents.size());
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Error processing container change at %s", position);
        }
    }

    /**
     * Scans the contents of an ItemContainer and returns as ItemEntry list.
     *
     * @param container The container to scan
     * @return List of items aggregated by ID
     */
    private List<ItemEntry> scanContainerContents(ItemContainer container) {
        Map<String, Integer> itemQuantities = new HashMap<>();

        container.forEach((slot, itemStack) -> {
            if (itemStack != null && !itemStack.isEmpty()) {
                String itemId = itemStack.getItemId();
                int quantity = itemStack.getQuantity();
                itemQuantities.merge(itemId, quantity, Integer::sum);
            }
        });

        List<ItemEntry> contents = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : itemQuantities.entrySet()) {
            contents.add(new ItemEntry(entry.getKey(), entry.getValue()));
        }

        return contents;
    }

    /**
     * Checks if a listener is registered for a warehouse position.
     *
     * @param position The warehouse position
     * @return true if a listener is registered
     */
    public boolean hasListenerRegistered(Vector3i position) {
        return containerListeners.containsKey(position);
    }

    /**
     * Gets the number of registered container listeners.
     */
    public int getRegisteredListenerCount() {
        return containerListeners.size();
    }

    /**
     * Returns whether the handler is using event-driven mode.
     */
    public boolean isEventModeEnabled() {
        return eventModeEnabled;
    }

    /**
     * Returns the scan interval in seconds (for fallback mode).
     */
    public long getScanIntervalSeconds() {
        return eventModeEnabled ? SCAN_INTERVAL_SECONDS : FALLBACK_ONLY_SCAN_INTERVAL_SECONDS;
    }
}
