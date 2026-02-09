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
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Detects when a player places a container block inside a colony and
 * opens a prompt asking whether to register it as a warehouse.
 */
@SuppressWarnings({"removal"})
public class ContainerPlaceEventSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double DEFAULT_COLONY_RADIUS = 100.0;

    private final ColonyService colonyService;
    private final WarehouseRegistry warehouseRegistry;
    private final InventoryCacheService inventoryCache;
    private final InventoryChangeHandler changeHandler;

    public ContainerPlaceEventSystem(ColonyService colonyService,
                                     WarehouseRegistry warehouseRegistry,
                                     InventoryCacheService inventoryCache,
                                     InventoryChangeHandler changeHandler) {
        super(PlaceBlockEvent.class);
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
                       @Nonnull PlaceBlockEvent event) {
        try {
            processPlacement(event, store, chunk, entityIndex);
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Error processing container placement");
        }
    }

    private void processPlacement(PlaceBlockEvent event, Store<EntityStore> store,
                                  ArchetypeChunk<EntityStore> chunk, int entityIndex) {
        Player player = chunk.getComponent(entityIndex, Player.getComponentType());
        PlayerRef playerRef = chunk.getComponent(entityIndex, PlayerRef.getComponentType());
        if (player == null || playerRef == null) return;

        World world = player.getWorld();
        Vector3i blockPos = event.getTargetBlock();
        if (world == null || blockPos == null) return;

        // Quick check: is this position within a colony?
        String worldId = world.getName() != null ? world.getName() : "default";
        ColonyData colony = findColonyContainingPosition(blockPos, worldId);
        if (colony == null) return;

        // PlaceBlockEvent fires before the block is placed.
        // Schedule a check after placement to verify it's a container.
        var entityRef = chunk.getReferenceTo(entityIndex);

        world.execute(() -> {
            try {
                long chunkKey = ChunkUtil.indexChunkFromBlock(blockPos.getX(), blockPos.getZ());
                WorldChunk worldChunk = world.getChunkIfLoaded(chunkKey);
                if (worldChunk == null) return;

                var blockState = worldChunk.getState(
                        ChunkUtil.localCoordinate(blockPos.getX()),
                        blockPos.getY(),
                        ChunkUtil.localCoordinate(blockPos.getZ())
                );
                if (!(blockState instanceof ItemContainerState containerState)) return;

                ItemContainer container = containerState.getItemContainer();
                if (container == null) return;

                // Already registered (e.g. from a previous placement at same position)
                if (colony.hasWarehouseAt(blockPos)) return;

                // Open the registration prompt
                PageManager pageManager = player.getPageManager();
                if (pageManager == null) return;

                WarehouseRegistrationPage page = new WarehouseRegistrationPage(
                        playerRef, colony, blockPos, world, container.getCapacity(),
                        warehouseRegistry, inventoryCache, changeHandler
                );

                pageManager.openCustomPage(entityRef, store, page);

            } catch (Exception e) {
                LOGGER.atWarning().withCause(e).log("Error checking placed block at %s", blockPos);
            }
        });
    }

    private ColonyData findColonyContainingPosition(Vector3i blockPos, String worldId) {
        for (ColonyData colony : colonyService.getAllColonies()) {
            if (!worldId.equals(colony.getWorldId())) continue;
            double dx = blockPos.getX() - colony.getCenterX();
            double dy = blockPos.getY() - colony.getCenterY();
            double dz = blockPos.getZ() - colony.getCenterZ();
            if ((dx * dx + dy * dy + dz * dz) <= (DEFAULT_COLONY_RADIUS * DEFAULT_COLONY_RADIUS)) return colony;
        }
        return null;
    }
}
