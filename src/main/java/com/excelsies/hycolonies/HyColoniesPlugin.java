package com.excelsies.hycolonies;

import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.colony.storage.ColonyStorage;
import com.excelsies.hycolonies.command.CitizenCommand;
import com.excelsies.hycolonies.command.ColonyCommand;
import com.excelsies.hycolonies.command.LogisticsCommand;
import com.excelsies.hycolonies.command.WarehouseCommand;
import com.excelsies.hycolonies.ecs.component.CitizenComponent;
import com.excelsies.hycolonies.ecs.component.InventoryComponent;
import com.excelsies.hycolonies.ecs.component.JobComponent;
import com.excelsies.hycolonies.ecs.component.PathingComponent;
import com.excelsies.hycolonies.ecs.system.CourierJobSystem;
import com.excelsies.hycolonies.ecs.system.LogisticsTickSystem;
import com.excelsies.hycolonies.ecs.system.MovementSystem;
import com.excelsies.hycolonies.interaction.CreateColonyInteraction;
import com.excelsies.hycolonies.logistics.event.InventoryChangeHandler;
import com.excelsies.hycolonies.ecs.tag.CourierActiveTag;
import com.excelsies.hycolonies.ecs.tag.IdleTag;
import com.excelsies.hycolonies.logistics.service.InventoryCacheService;
import com.excelsies.hycolonies.logistics.service.LogisticsService;
import com.excelsies.hycolonies.ui.ContainerBreakEventSystem;
import com.excelsies.hycolonies.ui.ContainerOpenEventSystem;
import com.excelsies.hycolonies.ui.ContainerPlaceEventSystem;
import com.excelsies.hycolonies.ui.PlayerContainerTracker;
import com.excelsies.hycolonies.warehouse.WarehouseRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * HyColonies - A colony management plugin for Hytale.
 *
 * This plugin enables players to establish autonomous settlements where
 * NPC citizens live, work, and build dynamically.
 *
 * Phase 1 implements:
 * - Colony data persistence with atomic writes
 * - Citizen data management
 * - Commands for colony creation and citizen management
 * - ECS CitizenComponent for marking entities as citizens
 * - NPC role templates for citizen wander/idle behavior
 *
 * Phase 2 implements:
 * - Logistics system with async solver
 * - Warehouse management and inventory caching
 * - JobComponent, PathingComponent, InventoryComponent
 * - Courier job state machine
 */
public class HyColoniesPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Singleton instance
    private static HyColoniesPlugin instance;
    private ColonyStorage colonyStorage;
    private ColonyService colonyService;
    private WarehouseRegistry warehouseRegistry;
    private InventoryCacheService inventoryCacheService;
    private LogisticsService logisticsService;
    private InventoryChangeHandler inventoryChangeHandler;

    // UI Handlers
    private ContainerOpenEventSystem containerOpenEventSystem;
    private ContainerPlaceEventSystem containerPlaceEventSystem;
    private ContainerBreakEventSystem containerBreakEventSystem;
    private PlayerContainerTracker playerContainerTracker;

    /**
     * Plugin constructor - called when the plugin is loaded.
     */
    public HyColoniesPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
        LOGGER.atInfo().log("HyColonies v" + getManifest().getVersion() + " loading...");
    }

    /**
     * Get the plugin instance.
     */
    public static HyColoniesPlugin get() {
        return instance;
    }

    /**
     * Plugin setup - register commands, components, systems, and initialize services.
     */
    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up HyColonies...");

        // Register ECS components and systems first
        registerECSComponents();
        registerECSSystems();
        
        // Register interactions
        registerInteractions();

        // Initialize storage and services
        initializeServices();

        // Register commands
        registerCommands();

        // Register UI event handlers
        registerEventHandlers();

        // Start auto-save scheduler
        startAutoSave();

        // Register shutdown hook for cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(this::onShutdown));

        LOGGER.atInfo().log("HyColonies setup complete!");
        LOGGER.atInfo().log("  - Registered 6 ECS components and 3 systems");
        LOGGER.atInfo().log("  - Registered 4 commands (/colony, /citizen, /warehouse, /logistics)");
        LOGGER.atInfo().log("  - Registered container event handlers (place prompt, open HUD, break cleanup)");
        LOGGER.atInfo().log("  - Loaded " + colonyService.getColonyCount() + " colonies from disk");
        LOGGER.atInfo().log("  - Logistics system initialized with inventory scanning");
    }

    /**
     * Registers custom ECS components.
     */
    private void registerECSComponents() {
        LOGGER.atInfo().log("Registering ECS components...");

        ComponentType<EntityStore, CitizenComponent> citizenComponentType =
                getEntityStoreRegistry().registerComponent(
                        CitizenComponent.class,
                        "HyColonies:CitizenComponent",
                        CitizenComponent.CODEC
                );
        CitizenComponent.setComponentType(citizenComponentType);
        LOGGER.atInfo().log("  - Registered CitizenComponent");

        ComponentType<EntityStore, JobComponent> jobComponentType =
                getEntityStoreRegistry().registerComponent(
                        JobComponent.class,
                        "HyColonies:JobComponent",
                        JobComponent.CODEC
                );
        JobComponent.setComponentType(jobComponentType);
        LOGGER.atInfo().log("  - Registered JobComponent");

        ComponentType<EntityStore, PathingComponent> pathingComponentType =
                getEntityStoreRegistry().registerComponent(
                        PathingComponent.class,
                        "HyColonies:PathingComponent",
                        PathingComponent.CODEC
                );
        PathingComponent.setComponentType(pathingComponentType);
        LOGGER.atInfo().log("  - Registered PathingComponent");

        ComponentType<EntityStore, InventoryComponent> inventoryComponentType =
                getEntityStoreRegistry().registerComponent(
                        InventoryComponent.class,
                        "HyColonies:InventoryComponent",
                        InventoryComponent.CODEC
                );
        InventoryComponent.setComponentType(inventoryComponentType);
        LOGGER.atInfo().log("  - Registered InventoryComponent");

        ComponentType<EntityStore, IdleTag> idleTagType =
                getEntityStoreRegistry().registerComponent(
                        IdleTag.class,
                        "HyColonies:IdleTag",
                        IdleTag.CODEC
                );
        IdleTag.setComponentType(idleTagType);
        LOGGER.atInfo().log("  - Registered IdleTag");

        ComponentType<EntityStore, CourierActiveTag> courierActiveTagType =
                getEntityStoreRegistry().registerComponent(
                        CourierActiveTag.class,
                        "HyColonies:CourierActiveTag",
                        CourierActiveTag.CODEC
                );
        CourierActiveTag.setComponentType(courierActiveTagType);
        LOGGER.atInfo().log("  - Registered CourierActiveTag");
    }

    /**
     * Registers custom ECS systems.
     */
    private void registerECSSystems() {
        LOGGER.atInfo().log("Registering ECS systems...");

        // Note: Idle wandering is handled by NPC role templates, not by an ECS system
        getEntityStoreRegistry().registerSystem(new MovementSystem());
        LOGGER.atInfo().log("  - Registered MovementSystem");

        // Note: LogisticsTickSystem and CourierJobSystem are registered after services are initialized
    }
    
    /**
     * Registers custom interactions.
     */
    private void registerInteractions() {
        LOGGER.atInfo().log("Registering interactions...");

        this.getCodecRegistry(Interaction.CODEC).register("CreateColonyInteraction", CreateColonyInteraction.class, CreateColonyInteraction.CODEC);
    }

    /**
     * Initializes the storage and service layers.
     */
    private void initializeServices() {
        LOGGER.atInfo().log("Initializing services...");

        // Get server run directory
        Path serverDirectory = Paths.get("").toAbsolutePath();

        colonyStorage = new ColonyStorage(serverDirectory);
        colonyService = new ColonyService(colonyStorage);
        colonyService.initialize();

        warehouseRegistry = new WarehouseRegistry();
        inventoryCacheService = new InventoryCacheService();
        logisticsService = new LogisticsService(inventoryCacheService, colonyService);

        // Register LogisticsTickSystem now that services are ready
        getEntityStoreRegistry().registerSystem(new LogisticsTickSystem(logisticsService));
        LOGGER.atInfo().log("  - Registered LogisticsTickSystem");

        // Register CourierJobSystem now that logisticsService is ready
        getEntityStoreRegistry().registerSystem(new CourierJobSystem(logisticsService, inventoryCacheService));
        LOGGER.atInfo().log("  - Registered CourierJobSystem");

        // Initialize and start InventoryChangeHandler
        inventoryChangeHandler = new InventoryChangeHandler(inventoryCacheService, warehouseRegistry, colonyService);
        inventoryChangeHandler.start(HytaleServer.SCHEDULED_EXECUTOR);
        LOGGER.atInfo().log("  - Started InventoryChangeHandler");

        // Initialize player container tracker (used by UI handlers and commands)
        playerContainerTracker = new PlayerContainerTracker();

        // Load existing warehouses into registry and cache
        loadWarehousesFromColonies();
    }

    /**
     * Loads existing warehouses from all colonies into the registry and cache.
     */
    private void loadWarehousesFromColonies() {
        for (var colony : colonyService.getAllColonies()) {
            for (var warehouse : colony.getWarehouses()) {
                warehouseRegistry.registerWarehouse(colony.getColonyId(), warehouse.getPosition());
                inventoryCacheService.registerWarehouse(colony.getColonyId(), warehouse.getPosition());
            }
        }
        LOGGER.atInfo().log("Loaded %d warehouses into registry", warehouseRegistry.getTotalWarehouseCount());
    }

    /**
     * Registers plugin commands.
     */
    private void registerCommands() {
        LOGGER.atInfo().log("Registering commands...");

        getCommandRegistry().registerCommand(
                new ColonyCommand(colonyService)
        );

        getCommandRegistry().registerCommand(
                new CitizenCommand(colonyService)
        );

        getCommandRegistry().registerCommand(
                new WarehouseCommand(colonyService, warehouseRegistry, inventoryCacheService, inventoryChangeHandler)
        );

        getCommandRegistry().registerCommand(
                new LogisticsCommand(logisticsService, inventoryCacheService, colonyService)
        );
    }

    /**
     * Registers UI event handlers.
     */
    private void registerEventHandlers() {
        LOGGER.atInfo().log("Registering event handlers...");

        // Container open event - shows warehouse status HUD overlay
        containerOpenEventSystem = new ContainerOpenEventSystem(colonyService);
        getEntityStoreRegistry().registerSystem(containerOpenEventSystem);
        LOGGER.atInfo().log("  - Registered ContainerOpenEventSystem for UseBlockEvent.Post");

        // Container place event - shows warehouse registration prompt
        containerPlaceEventSystem = new ContainerPlaceEventSystem(
                colonyService, warehouseRegistry, inventoryCacheService, inventoryChangeHandler
        );
        getEntityStoreRegistry().registerSystem(containerPlaceEventSystem);
        LOGGER.atInfo().log("  - Registered ContainerPlaceEventSystem for PlaceBlockEvent");

        // Container break event - auto-unregisters warehouses
        containerBreakEventSystem = new ContainerBreakEventSystem(
                colonyService, warehouseRegistry, inventoryCacheService, inventoryChangeHandler
        );
        getEntityStoreRegistry().registerSystem(containerBreakEventSystem);
        LOGGER.atInfo().log("  - Registered ContainerBreakEventSystem for BreakBlockEvent");
    }

    /**
     * Starts the auto-save scheduler.
     */
    private void startAutoSave() {
        colonyService.startAutoSave(HytaleServer.SCHEDULED_EXECUTOR);
    }

    /**
     * Called on JVM shutdown to save data.
     */
    private void onShutdown() {
        LOGGER.atInfo().log("HyColonies shutting down...");

        if (inventoryChangeHandler != null) {
            inventoryChangeHandler.stop();
        }

        if (logisticsService != null) {
            logisticsService.shutdown();
        }

        if (colonyService != null) {
            colonyService.shutdown();
        }

        LOGGER.atInfo().log("HyColonies shutdown complete. Goodbye!");
    }

    // === Getters for services ===

    /**
     * Gets the colony service.
     */
    public ColonyService getColonyService() {
        return colonyService;
    }

    /**
     * Gets the warehouse registry.
     */
    public WarehouseRegistry getWarehouseRegistry() {
        return warehouseRegistry;
    }

    /**
     * Gets the inventory cache service.
     */
    public InventoryCacheService getInventoryCacheService() {
        return inventoryCacheService;
    }

    /**
     * Gets the logistics service.
     */
    public LogisticsService getLogisticsService() {
        return logisticsService;
    }

    /**
     * Gets the inventory change handler.
     */
    public InventoryChangeHandler getInventoryChangeHandler() {
        return inventoryChangeHandler;
    }

    /**
     * Gets the player container tracker.
     */
    public PlayerContainerTracker getPlayerContainerTracker() {
        return playerContainerTracker;
    }
}