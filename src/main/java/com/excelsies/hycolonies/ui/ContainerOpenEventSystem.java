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
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;

/**
 * Shows a display-only HUD overlay when a player opens a container
 * inside a colony, indicating its warehouse registration status.
 *
 * Does NOT cancel vanilla container handling - the container opens normally
 * and the HUD appears on top.
 */
@SuppressWarnings({"removal"})
public class ContainerOpenEventSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Post> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double DEFAULT_COLONY_RADIUS = 100.0;

    private final ColonyService colonyService;

    public ContainerOpenEventSystem(ColonyService colonyService) {
        super(UseBlockEvent.Post.class);
        this.colonyService = colonyService;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int entityIndex, @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull UseBlockEvent.Post event) {
        try {
            processContainerOpen(event, chunk, entityIndex, commandBuffer);
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Error processing container open for HUD");
        }
    }

    private void processContainerOpen(UseBlockEvent.Post event,
                                      ArchetypeChunk<EntityStore> chunk, int entityIndex,
                                      CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef playerRef = chunk.getComponent(entityIndex, PlayerRef.getComponentType());
        Player player = chunk.getComponent(entityIndex, Player.getComponentType());
        if (playerRef == null || player == null) return;

        World world = player.getWorld();
        Vector3i blockPos = event.getTargetBlock();
        if (world == null || blockPos == null) return;

        // Check if the block is a container
        long chunkKey = ChunkUtil.indexChunkFromBlock(blockPos.getX(), blockPos.getZ());
        WorldChunk worldChunk = world.getChunkIfLoaded(chunkKey);
        if (worldChunk == null) return;

        var blockState = worldChunk.getState(
                ChunkUtil.localCoordinate(blockPos.getX()),
                blockPos.getY(),
                ChunkUtil.localCoordinate(blockPos.getZ())
        );
        if (!(blockState instanceof ItemContainerState containerState)) return;

        // Check if this container is within a colony
        String worldId = world.getName() != null ? world.getName() : "default";
        ColonyData colony = findColonyContainingPosition(blockPos, worldId);
        if (colony == null) return;

        // Get player UUID for window lookup
        var entityRef = chunk.getReferenceTo(entityIndex);
        UUIDComponent uuidComponent = commandBuffer.getComponent(entityRef, UUIDComponent.getComponentType());
        UUID playerUuid = uuidComponent != null ? uuidComponent.getUuid() : playerRef.getUuid();

        // Defer to next tick to check if a container window was actually opened.
        // UseBlockEvent.Post fires for any interaction, including breaking.
        HudManager hudManager = player.getHudManager();
        if (hudManager == null) return;

        world.execute(() -> {
            try {
                Map<UUID, ContainerBlockWindow> windows = containerState.getWindows();
                ContainerBlockWindow window = windows.get(playerUuid);

                // Only show HUD if the player actually opened the container
                if (window == null) return;

                int capacity = containerState.getItemContainer() != null
                        ? containerState.getItemContainer().getCapacity() : 0;
                ColonyContainerHud hud = new ColonyContainerHud(playerRef, colony, blockPos, capacity);
                hudManager.setCustomHud(playerRef, hud);

                // Register close event to hide the HUD when container closes.
                window.registerCloseEvent(closeEvent -> {
                    world.execute(() -> {
                        try {
                            hud.hide();
                        } catch (Exception e) {
                            LOGGER.atWarning().withCause(e).log("Error hiding HUD on container close");
                        }
                    });
                });
            } catch (Exception e) {
                LOGGER.atWarning().withCause(e).log("Error showing container HUD");
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
