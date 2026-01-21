package com.excelsies.hycolonies.logistics.event;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.logistics.model.ItemEntry;
import com.excelsies.hycolonies.logistics.service.InventoryCacheService;
import com.excelsies.hycolonies.warehouse.WarehouseData;
import com.excelsies.hycolonies.warehouse.WarehouseRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.math.util.ChunkUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles inventory change events for warehouse blocks.
 *
 * Primary mode: Event-driven updates via ItemContainerChangeEvent (if available).
 * Fallback mode: Periodic scanning every 5 seconds.
 *
 * This implementation uses the fallback mode since the Hytale API for
 * ItemContainerChangeEvent may not be exposed to plugins yet.
 */
public class InventoryChangeHandler {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Scan interval in seconds for fallback periodic scanning.
     */
    private static final long SCAN_INTERVAL_SECONDS = 5;

    private final InventoryCacheService cacheService;
    private final WarehouseRegistry warehouseRegistry;
    private final ColonyService colonyService;

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
     * Attempts to register for events; falls back to periodic scanning if unavailable.
     *
     * @param executor The scheduled executor service for fallback scanning
     */
    public void start(ScheduledExecutorService executor) {
        // Try to enable event-driven mode
        if (tryEnableEventMode()) {
            LOGGER.atInfo().log("InventoryChangeHandler: Event-driven mode enabled");
            eventModeEnabled = true;
        } else {
            // Fall back to periodic scanning
            LOGGER.atInfo().log("InventoryChangeHandler: Using fallback periodic scanning (every %ds)",
                    SCAN_INTERVAL_SECONDS);
            startPeriodicScan(executor);
        }
    }

    /**
     * Stops the inventory change handler.
     */
    public void stop() {
        if (scanTask != null && !scanTask.isCancelled()) {
            scanTask.cancel(false);
            scanTask = null;
        }

        if (eventModeEnabled) {
            // Unregister event listener if we were using event mode
            eventModeEnabled = false;
        }

        LOGGER.atInfo().log("InventoryChangeHandler stopped");
    }

    /**
     * Attempts to enable event-driven mode by registering for inventory change events.
     *
     * @return true if event mode was successfully enabled, false otherwise
     */
    private boolean tryEnableEventMode() {
        // TODO: When Hytale exposes ItemContainerChangeEvent, register listener here:
        //
        // try {
        //     eventRegistry.registerGlobal(ItemContainerChangeEvent.class, this::onInventoryChange);
        //     return true;
        // } catch (Exception e) {
        //     LOGGER.atWarning().log("ItemContainerChangeEvent not available: %s", e.getMessage());
        //     return false;
        // }

        // For now, always return false to use fallback mode
        return false;
    }

    /**
     * Starts the periodic warehouse scan task.
     */
    private void startPeriodicScan(ScheduledExecutorService executor) {
        scanTask = executor.scheduleAtFixedRate(
                this::scanAllWarehouses,
                SCAN_INTERVAL_SECONDS,
                SCAN_INTERVAL_SECONDS,
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

            // Use getState which returns BlockState (deprecated but functional)
            @SuppressWarnings("deprecation")
            var blockState = chunk.getState(localX, localY, localZ);

            if (blockState instanceof ItemContainerState containerState) {
                ItemContainer container = containerState.getItemContainer();
                if (container != null) {
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
        return SCAN_INTERVAL_SECONDS;
    }
}
