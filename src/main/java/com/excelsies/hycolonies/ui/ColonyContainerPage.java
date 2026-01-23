package com.excelsies.hycolonies.ui;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.logistics.event.InventoryChangeHandler;
import com.excelsies.hycolonies.logistics.service.InventoryCacheService;
import com.excelsies.hycolonies.warehouse.WarehouseData;
import com.excelsies.hycolonies.warehouse.WarehouseRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.UUID;

public class ColonyContainerPage extends CustomUIPage {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final ColonyData colony;
    private final Vector3i blockPos;
    private final World world;
    private final int capacity;
    private final ItemContainer container;
    
    // Services
    private final PlayerContainerTracker containerTracker;
    private final WarehouseRegistry warehouseRegistry;
    private final InventoryCacheService inventoryCache;
    private final InventoryChangeHandler changeHandler;

    public ColonyContainerPage(
            PlayerRef playerRef,
            ColonyData colony,
            Vector3i blockPos,
            World world,
            int capacity,
            ItemContainer container,
            PlayerContainerTracker containerTracker,
            WarehouseRegistry warehouseRegistry,
            InventoryCacheService inventoryCache,
            InventoryChangeHandler changeHandler) {
        super(playerRef, CustomPageLifetime.CanDismiss);
        this.colony = colony;
        this.blockPos = blockPos;
        this.world = world;
        this.capacity = capacity;
        this.container = container;
        this.containerTracker = containerTracker;
        this.warehouseRegistry = warehouseRegistry;
        this.inventoryCache = inventoryCache;
        this.changeHandler = changeHandler;
    }

    @Override
    public void build(Ref<EntityStore> entityRef, UICommandBuilder commandBuilder, UIEventBuilder eventBuilder, Store<EntityStore> store) {
        // Load the UI file - this is relative to the resources/Common/UI/Custom folder
        commandBuilder.append("Pages/ColonyContainer.ui");

        // Initial state
        boolean isRegistered = colony.hasWarehouseAt(blockPos);

        // Set variables with corrected property name '.Value' for CheckBox
        commandBuilder.set("#WarehouseToggle.Value", isRegistered);
        commandBuilder.set("#TitleLabel.Text", isRegistered ? "Warehouse" : "Container");

        // Bind events
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#WarehouseToggle");
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> entityRef, Store<EntityStore> store, String data) {
        LOGGER.atInfo().log("Received data event: %s", data);
        
        // Execute on world thread to ensure thread safety
        world.execute(() -> toggleWarehouseStatus(entityRef, store));
    }

    private void toggleWarehouseStatus(Ref<EntityStore> entityRef, Store<EntityStore> store) {
        boolean currentlyRegistered = colony.hasWarehouseAt(blockPos);
        boolean newStatus = !currentlyRegistered;
        UUID colonyId = colony.getColonyId();
        
        // Use 'default' if world name is null
        String worldId = world.getName() != null ? world.getName() : "default";

        try {
            if (newStatus) {
                // Register logic
                WarehouseData warehouse = new WarehouseData(blockPos, "Container", capacity, worldId);
                colony.addWarehouse(warehouse);
                warehouseRegistry.registerWarehouse(colonyId, blockPos);
                inventoryCache.registerWarehouse(colonyId, blockPos);
                changeHandler.registerContainerListener(colonyId, blockPos, world);
                
                LOGGER.atInfo().log("Registered warehouse at %s via UI", blockPos);
            } else {
                // Unregister logic
                changeHandler.unregisterContainerListener(blockPos);
                colony.removeWarehouse(blockPos);
                warehouseRegistry.unregisterWarehouse(blockPos);
                inventoryCache.unregisterWarehouse(colonyId, blockPos);
                
                LOGGER.atInfo().log("Unregistered warehouse at %s via UI", blockPos);
            }

            // Update tracker
            containerTracker.updateRegistrationStatus(playerRef.getUuid(), newStatus);

            // Send updates to UI
            UICommandBuilder update = new UICommandBuilder();
            update.set("#WarehouseToggle.Value", newStatus);
            update.set("#TitleLabel.Text", newStatus ? "Warehouse" : "Container");
            
            sendUpdate(update);
            
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed to toggle warehouse status via UI");
        }
    }
}