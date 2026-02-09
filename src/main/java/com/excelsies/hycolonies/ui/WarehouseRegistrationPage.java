package com.excelsies.hycolonies.ui;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.logistics.event.InventoryChangeHandler;
import com.excelsies.hycolonies.logistics.service.InventoryCacheService;
import com.excelsies.hycolonies.warehouse.WarehouseData;
import com.excelsies.hycolonies.warehouse.WarehouseRegistry;
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
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/**
 * Prompt page shown when a player places a container inside a colony.
 * Asks whether the container should be registered as a colony warehouse.
 */
public class WarehouseRegistrationPage extends InteractiveCustomUIPage<WarehouseRegistrationPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ColonyData colony;
    private final Vector3i blockPos;
    private final World world;
    private final int capacity;

    // Services
    private final WarehouseRegistry warehouseRegistry;
    private final InventoryCacheService inventoryCache;
    private final InventoryChangeHandler changeHandler;

    public WarehouseRegistrationPage(PlayerRef playerRef, ColonyData colony, Vector3i blockPos,
                                     World world, int capacity,
                                     WarehouseRegistry warehouseRegistry,
                                     InventoryCacheService inventoryCache,
                                     InventoryChangeHandler changeHandler) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.colony = colony;
        this.blockPos = blockPos;
        this.world = world;
        this.capacity = capacity;
        this.warehouseRegistry = warehouseRegistry;
        this.inventoryCache = inventoryCache;
        this.changeHandler = changeHandler;
    }

    @Override
    public void build(Ref<EntityStore> entityRef, UICommandBuilder commandBuilder,
                      UIEventBuilder eventBuilder, Store<EntityStore> store) {
        commandBuilder.append("Pages/WarehouseRegistration.ui");

        // Set colony name in the description
        commandBuilder.set("#ColonyName.Text", colony.getName());

        // Bind button events
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#RegisterButton",
                EventData.of("Button", "RegisterButton"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                EventData.of("Button", "CancelButton"), false);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> entityRef, Store<EntityStore> store, Data data) {
        super.handleDataEvent(entityRef, store, data);
        if (data == null || data.key == null) return;

        switch (data.key) {
            case "RegisterButton" -> world.execute(this::handleRegister);
            case "CancelButton" -> close();
        }
    }

    private void handleRegister() {
        UUID colonyId = colony.getColonyId();
        String worldId = world.getName() != null ? world.getName() : "default";

        try {
            WarehouseData warehouse = new WarehouseData(blockPos, "Container", capacity, worldId);
            colony.addWarehouse(warehouse);
            warehouseRegistry.registerWarehouse(colonyId, blockPos);
            inventoryCache.registerWarehouse(colonyId, blockPos);
            changeHandler.registerContainerListener(colonyId, blockPos, world);

            LOGGER.atInfo().log("Warehouse registered at %s in colony '%s'", blockPos, colony.getName());
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed to register warehouse at %s", blockPos);
        }

        close();
    }

    @Override
    public void onDismiss(Ref<EntityStore> entityRef, Store<EntityStore> store) {
        super.onDismiss(entityRef, store);
    }

    public static class Data {
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Button", Codec.STRING),
                        (data, value) -> data.key = value,
                        data -> data.key).add()
                .build();

        private String key;
    }
}
