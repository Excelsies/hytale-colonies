package com.excelsies.hycolonies.ui;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.logistics.event.InventoryChangeHandler;
import com.excelsies.hycolonies.logistics.service.InventoryCacheService;
import com.excelsies.hycolonies.warehouse.WarehouseRegistry;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.UUID;

/**
 * Detects when a registered warehouse container is broken and
 * automatically unregisters it from all services.
 */
public class ContainerBreakEventSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ColonyService colonyService;
    private final WarehouseRegistry warehouseRegistry;
    private final InventoryCacheService inventoryCache;
    private final InventoryChangeHandler changeHandler;

    public ContainerBreakEventSystem(ColonyService colonyService,
                                     WarehouseRegistry warehouseRegistry,
                                     InventoryCacheService inventoryCache,
                                     InventoryChangeHandler changeHandler) {
        super(BreakBlockEvent.class);
        this.colonyService = colonyService;
        this.warehouseRegistry = warehouseRegistry;
        this.inventoryCache = inventoryCache;
        this.changeHandler = changeHandler;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int entityIndex, @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull BreakBlockEvent event) {
        try {
            processBreak(event);
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Error processing container break");
        }
    }

    private void processBreak(BreakBlockEvent event) {
        Vector3i blockPos = event.getTargetBlock();
        if (blockPos == null) return;

        // Check if this position is a registered warehouse
        UUID colonyId = warehouseRegistry.getColonyForWarehouse(blockPos);
        if (colonyId == null) return;

        // Unregister the warehouse from all services
        changeHandler.unregisterContainerListener(blockPos);

        Optional<ColonyData> colonyOpt = colonyService.getColony(colonyId);
        colonyOpt.ifPresent(colony -> colony.removeWarehouse(blockPos));

        warehouseRegistry.unregisterWarehouse(blockPos);
        inventoryCache.unregisterWarehouse(colonyId, blockPos);

        LOGGER.atInfo().log("Warehouse at %s unregistered (container broken)", blockPos);
    }
}
