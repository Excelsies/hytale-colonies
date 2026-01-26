package com.excelsies.hycolonies.ui;

import com.excelsies.hycolonies.colony.model.Faction;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * UI page for creating a new colony.
 *
 * After colony creation, places the Colony Banner block at the target position.
 */
public class ColonyCreationPage extends InteractiveCustomUIPage<ColonyCreationPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Faction[] FACTIONS = Faction.values();

    private final ColonyService colonyService;
    private final Vector3i targetPos;
    private final World world;

    // Block placement data
    private final ItemStack heldItem;
    private final ItemContainer heldItemContainer;
    private final byte heldItemSlot;

    private String colonyName = "";
    private int factionIndex = 0;

    public ColonyCreationPage(PlayerRef playerRef, ColonyService colonyService,
                              Vector3i targetPos, World world,
                              ItemStack heldItem, ItemContainer heldItemContainer,
                              byte heldItemSlot) {
        super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.colonyService = colonyService;
        this.targetPos = targetPos;
        this.world = world;
        this.heldItem = heldItem;
        this.heldItemContainer = heldItemContainer;
        this.heldItemSlot = heldItemSlot;
    }

    @Override
    public void build(Ref<EntityStore> entityRef, UICommandBuilder commandBuilder,
                      UIEventBuilder eventBuilder, Store<EntityStore> store) {
        commandBuilder.append("Pages/ColonyCreation.ui");

        // Bind text input events
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#NameInput",
                EventData.of("@NameInput", "#NameInput.Value"), false);

        // Bind button activation events
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CreateButton",
                EventData.of("Button", "CreateButton"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                EventData.of("Button", "CancelButton"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#PrevFaction",
                EventData.of("Button", "PrevFaction"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#NextFaction",
                EventData.of("Button", "NextFaction"), false);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> entityRef, Store<EntityStore> store, Data data) {
        super.handleDataEvent(entityRef, store, data);

        if (data == null) return;

        try {
            switch (data.key) {
                case "NameInput" -> nameTextChange(data.value);
                case "CreateButton" -> handleCreate();
                case "CancelButton" -> close();
                case "PrevFaction" -> cycleFaction(-1);
                case "NextFaction" -> cycleFaction(1);
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Error handling UI event: %s", data);
        } finally {
            sendUpdate();
        }
    }

    private void nameTextChange(String text) {
        this.colonyName = text;
        UICommandBuilder update = new UICommandBuilder();
        update.set("#ErrorLabel.Text", "");
    }

    private void cycleFaction(int direction) {
        factionIndex = (factionIndex + direction + FACTIONS.length) % FACTIONS.length;

        UICommandBuilder update = new UICommandBuilder();
        updateFactionDisplay(update);
        sendUpdate(update);
    }

    private void updateFactionDisplay(UICommandBuilder builder) {
        Faction faction = FACTIONS[factionIndex];
        builder.set("#FactionName.Text", faction.getDisplayName());

        // Toggle visibility of faction images
        for (Faction f : FACTIONS) {
            String selector = "#Img" + f.getDisplayName() + ".Visible";
            builder.set(selector, f == faction);
        }
    }

    private Faction getSelectedFaction() {
        return FACTIONS[factionIndex];
    }

    private void handleCreate() {
        // Validate colony name
        if (colonyName == null || colonyName.trim().isEmpty()) {
            showError("Colony name cannot be empty.");
            return;
        }

        if (!colonyService.getColoniesByName(colonyName).isEmpty()) {
            showError("A colony with that name already exists.");
            return;
        }

        Faction faction = getSelectedFaction();

        // Create Colony and place block
        world.execute(() -> {
            try {
                String worldId = world.getName() != null ? world.getName() : "default";

                colonyService.createColony(
                        colonyName,
                        playerRef.getUuid(),
                        targetPos.getX() + 0.5,
                        targetPos.getY(),
                        targetPos.getZ() + 0.5,
                        worldId,
                        faction
                );

                LOGGER.atInfo().log("Created colony '%s' (%s) at %s",
                        colonyName, faction.getDisplayName(), targetPos);

                // Place the banner block
                placeBannerBlock();

                close();

            } catch (Exception e) {
                LOGGER.atWarning().withCause(e).log("Failed to create colony via UI");
                showError("An internal error occurred.");
            }
        });
    }

    /**
     * Places the Colony Banner block at the target position.
     */
    private void placeBannerBlock() {
        if (heldItem == null || ItemStack.isEmpty(heldItem)) {
            LOGGER.atWarning().log("Cannot place banner: no held item");
            return;
        }

        String blockKey = heldItem.getBlockKey();
        if (blockKey == null || blockKey.isEmpty()) {
            LOGGER.atWarning().log("Cannot place banner: held item has no block type");
            return;
        }

        // Use World.setBlock() for simple block placement
        world.setBlock(targetPos.getX(), targetPos.getY(), targetPos.getZ(), blockKey);
        LOGGER.atInfo().log("Placed colony banner block '%s' at %s", blockKey, targetPos);

        // Remove one item from the player's hand
        if (heldItemContainer != null) {
            heldItemContainer.removeItemStackFromSlot(heldItemSlot, 1);
        }
    }

    private void showError(String message) {
        UICommandBuilder update = new UICommandBuilder();
        update.set("#ErrorLabel.Text", message);
        sendUpdate(update);
    }

    public static class Data {
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("@NameInput", Codec.STRING),
                        (data, value) -> { data.key = "NameInput"; data.value = value; },
                        data -> data.value).add()
                .append(new KeyedCodec<>("Button", Codec.STRING),
                        (data, value) -> data.key = value,
                        data -> data.key).add()
                .build();

        private String key;
        private String value;
    }
}
