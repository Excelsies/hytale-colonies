package com.excelsies.hycolonies.interaction;

import com.excelsies.hycolonies.HyColoniesPlugin;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.ui.ColonyCreationPage;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.Interaction;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.PlaceBlockInteraction;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Custom interaction for creating colonies.
 *
 * Opens the ColonyCreationPage UI when player right-clicks with Colony Banner.
 * Block placement is handled by the UI page after colony creation.
 */
public class CreateColonyInteraction extends SimpleInteraction {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final BuilderCodec<CreateColonyInteraction> CODEC =
            BuilderCodec.builder(CreateColonyInteraction.class, CreateColonyInteraction::new).build();

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        // Request client data like PlaceBlockInteraction does
        return WaitForDataFrom.Client;
    }

    @Override
    protected Interaction generatePacket() {
        // Return PlaceBlockInteraction protocol packet so client sends block position data
        return new PlaceBlockInteraction();
    }

    @Override
    public boolean needsRemoteSync() {
        return true;
    }

    @Override
    protected void tick0(boolean firstRun, float time, InteractionType type,
                         InteractionContext context, CooldownHandler cooldownHandler) {
        // Only run on first tick
        if (!firstRun) {
            return;
        }

        // Get client state which contains the placement position (already offset by face)
        InteractionSyncData clientState = context.getClientState();
        if (clientState == null || clientState.blockPosition == null) {
            LOGGER.atWarning().log("CreateColonyInteraction: No client state or block position");
            return;
        }

        Ref<EntityStore> entityRef = context.getEntity();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            return;
        }

        Player player = commandBuffer.getComponent(entityRef, Player.getComponentType());
        PlayerRef playerRef = commandBuffer.getComponent(entityRef, PlayerRef.getComponentType());

        if (player == null || playerRef == null) {
            return;
        }

        // Use blockPosition from client state - this is the placement position (already offset)
        BlockPosition placementBlock = clientState.blockPosition;
        Vector3i placementPos = new Vector3i(placementBlock.x, placementBlock.y, placementBlock.z);

        LOGGER.atInfo().log("CreateColonyInteraction: Placement position from client: x=%d y=%d z=%d",
                placementPos.getX(), placementPos.getY(), placementPos.getZ());

        // Get held item info for block placement
        ItemStack heldItem = context.getHeldItem();
        ItemContainer heldItemContainer = context.getHeldItemContainer();
        byte heldItemSlot = context.getHeldItemSlot();

        if (heldItem == null || heldItemContainer == null) {
            return;
        }

        ColonyService colonyService = HyColoniesPlugin.get().getColonyService();

        // Create and open the UI page
        ColonyCreationPage page = new ColonyCreationPage(
                playerRef,
                colonyService,
                placementPos,
                player.getWorld(),
                heldItem,
                heldItemContainer,
                heldItemSlot
        );

        if (player.getPageManager() != null) {
            var store = entityRef.getStore();
            if (store != null) {
                player.getPageManager().openCustomPage(entityRef, store, page);
            }
        }
    }
}
