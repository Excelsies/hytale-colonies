package com.excelsies.hycolonies.ui;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
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
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * ECS Event System that handles UseBlockEvent.Post to show the warehouse registration HUD
 * when players open containers inside colony territories.
 *
 * <p>Block events in Hytale must be handled via EntityEventSystem rather than
 * simple event registration.
 */
@SuppressWarnings({"removal"})
public class ContainerOpenEventSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Post> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Default colony radius for finding colonies
    private static final double DEFAULT_COLONY_RADIUS = 100.0;

    private final ColonyService colonyService;
    private final PlayerContainerTracker containerTracker;
    private final com.excelsies.hycolonies.warehouse.WarehouseRegistry warehouseRegistry;
    private final com.excelsies.hycolonies.logistics.service.InventoryCacheService inventoryCache;
    private final com.excelsies.hycolonies.logistics.event.InventoryChangeHandler changeHandler;

    public ContainerOpenEventSystem(ColonyService colonyService, PlayerContainerTracker containerTracker,
                                    com.excelsies.hycolonies.warehouse.WarehouseRegistry warehouseRegistry,
                                    com.excelsies.hycolonies.logistics.service.InventoryCacheService inventoryCache,
                                    com.excelsies.hycolonies.logistics.event.InventoryChangeHandler changeHandler) {
        super(UseBlockEvent.Post.class);
        this.colonyService = colonyService;
        this.containerTracker = containerTracker;
        this.warehouseRegistry = warehouseRegistry;
        this.inventoryCache = inventoryCache;
        this.changeHandler = changeHandler;
        LOGGER.atInfo().log("ContainerOpenEventSystem created for UseBlockEvent.Post");
    }

    @Override
    public Query<EntityStore> getQuery() {
        // Handle events for all entities (we filter by PlayerRef in the handler)
        return Query.any();
    }

    @Override
    public void handle(
            int entityIndex,
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull UseBlockEvent.Post event) {

        LOGGER.atInfo().log("UseBlockEvent.Post handled for block: %s", event.getTargetBlock());

        try {
            processContainerInteraction(event, store, chunk, entityIndex);
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Error processing container interaction");
        }
    }

    /**
     * Processes a container interaction event.
     */
    private void processContainerInteraction(
            UseBlockEvent.Post event,
            Store<EntityStore> store,
            ArchetypeChunk<EntityStore> chunk,
            int entityIndex) {

        // Get PlayerRef from the entity
        PlayerRef playerRef = chunk.getComponent(entityIndex, PlayerRef.getComponentType());
        if (playerRef == null) {
            LOGGER.atFine().log("Not a player interaction - no PlayerRef");
            return;
        }

        // Get Player component
        Player player = chunk.getComponent(entityIndex, Player.getComponentType());
        if (player == null) {
            LOGGER.atFine().log("Player component not found");
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            LOGGER.atFine().log("Player world is null");
            return;
        }

        // Get the block position from the event
        Vector3i blockPos = event.getTargetBlock();
        if (blockPos == null) {
            LOGGER.atFine().log("Target block position is null");
            return;
        }

        LOGGER.atInfo().log("Processing container interaction at %s", blockPos);

        // Check if the block is a container
        long chunkKey = ChunkUtil.indexChunkFromBlock(blockPos.getX(), blockPos.getZ());
        WorldChunk worldChunk = world.getChunkIfLoaded(chunkKey);
        if (worldChunk == null) {
            LOGGER.atFine().log("Chunk not loaded at %s", blockPos);
            return;
        }

        int localX = ChunkUtil.localCoordinate(blockPos.getX());
        int localY = blockPos.getY();
        int localZ = ChunkUtil.localCoordinate(blockPos.getZ());

        var blockState = worldChunk.getState(localX, localY, localZ);
        if (!(blockState instanceof ItemContainerBlockState containerState)) {
            LOGGER.atFine().log("Block at %s is not a container", blockPos);
            return;
        }

        ItemContainer container = containerState.getItemContainer();
        if (container == null) {
            LOGGER.atFine().log("Container at %s has no ItemContainer", blockPos);
            return;
        }

        LOGGER.atInfo().log("Found container at %s with capacity %d", blockPos, container.getCapacity());

        // Check if this container is inside any colony
        String worldId = world.getName() != null ? world.getName() : "default";
        LOGGER.atInfo().log("Checking container at %s in world '%s' for colony membership", blockPos, worldId);

        ColonyData colony = findColonyContainingPosition(blockPos, worldId);
        if (colony == null) {
            LOGGER.atFine().log("Container at %s is not inside any colony", blockPos);
            return;
        }

        LOGGER.atInfo().log("Container at %s is in colony '%s' (center: %.1f, %.1f, %.1f)",
                blockPos, colony.getName(), colony.getCenterX(), colony.getCenterY(), colony.getCenterZ());

        // Check if already registered as a warehouse
        boolean isRegistered = colony.hasWarehouseAt(blockPos);

        // Get container capacity
        int capacity = container.getCapacity();

        // Get player UUID for tracking
        UUID playerUuid = playerRef.getUuid();

        // Track this container for the player
        containerTracker.setViewingContainer(playerUuid, new PlayerContainerTracker.ViewingContainer(
                colony.getColonyId(),
                colony.getName(),
                blockPos,
                world,
                capacity,
                isRegistered
        ));

        // Show the warehouse registration page
        showWarehouseRegistrationPage(player, store, chunk, entityIndex, colony, blockPos, world, capacity, container, isRegistered);
    }

    /**
     * Shows the warehouse registration page for a container.
     */
    private void showWarehouseRegistrationPage(
            Player player,
            Store<EntityStore> store,
            ArchetypeChunk<EntityStore> chunk,
            int entityIndex,
            ColonyData colony,
            Vector3i blockPos,
            World world,
            int capacity,
            ItemContainer container,
            boolean isRegistered) {

        try {
            PageManager pageManager = player.getPageManager();
            if (pageManager != null) {
                LOGGER.atInfo().log("Opening custom page via PageManager");

                ColonyContainerPage page = new ColonyContainerPage(
                        player.getPlayerRef(),
                        colony,
                        blockPos,
                        world,
                        capacity,
                        container,
                        containerTracker,
                        warehouseRegistry,
                        inventoryCache,
                        changeHandler
                );

                // Get entity ref for opening page
                var entityRef = chunk.getReferenceTo(entityIndex);
                pageManager.openCustomPage(entityRef, store, page);
                LOGGER.atInfo().log("Page opened for container at %s in colony %s", blockPos, colony.getName());
            } else {
                LOGGER.atWarning().log("PageManager is null for player");
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed to show warehouse registration page");
        }
    }

    /**
     * Finds the colony that contains the given block position.
     */
    private ColonyData findColonyContainingPosition(Vector3i blockPos, String worldId) {
        for (ColonyData colony : colonyService.getAllColonies()) {
            if (!worldId.equals(colony.getWorldId())) {
                continue;
            }

            double centerX = colony.getCenterX();
            double centerY = colony.getCenterY();
            double centerZ = colony.getCenterZ();

            double dx = blockPos.getX() - centerX;
            double dy = blockPos.getY() - centerY;
            double dz = blockPos.getZ() - centerZ;
            double distanceSquared = dx * dx + dy * dy + dz * dz;

            double radiusSquared = DEFAULT_COLONY_RADIUS * DEFAULT_COLONY_RADIUS;

            if (distanceSquared <= radiusSquared) {
                return colony;
            }
        }

        return null;
    }
}
