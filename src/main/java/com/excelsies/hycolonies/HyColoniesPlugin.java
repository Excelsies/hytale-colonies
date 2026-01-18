package com.excelsies.hycolonies;

import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.colony.storage.ColonyStorage;
import com.excelsies.hycolonies.command.CitizenCommand;
import com.excelsies.hycolonies.command.ColonyCommand;
import com.excelsies.hycolonies.ecs.component.CitizenComponent;
import com.excelsies.hycolonies.ecs.system.WanderSystem;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
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
 * - WanderSystem for basic citizen behavior
 */
public class HyColoniesPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Singleton instance
    private static HyColoniesPlugin instance;

    // Services
    private ColonyStorage colonyStorage;
    private ColonyService colonyService;

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

        // Initialize storage and services
        initializeServices();

        // Register commands
        registerCommands();

        // Start auto-save scheduler
        startAutoSave();

        // Register shutdown hook for cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(this::onShutdown));

        LOGGER.atInfo().log("HyColonies setup complete!");
        LOGGER.atInfo().log("  - Registered CitizenComponent and WanderSystem");
        LOGGER.atInfo().log("  - Registered 2 commands (/colony, /citizen)");
        LOGGER.atInfo().log("  - Loaded " + colonyService.getColonyCount() + " colonies from disk");
    }

    /**
     * Registers custom ECS components.
     */
    private void registerECSComponents() {
        LOGGER.atInfo().log("Registering ECS components...");

        // Register CitizenComponent
        ComponentType<EntityStore, CitizenComponent> citizenComponentType =
                getEntityStoreRegistry().registerComponent(
                        CitizenComponent.class,
                        "HyColonies:CitizenComponent",
                        CitizenComponent.CODEC
                );
        CitizenComponent.setComponentType(citizenComponentType);

        LOGGER.atInfo().log("  - Registered CitizenComponent");
    }

    /**
     * Registers custom ECS systems.
     */
    private void registerECSSystems() {
        LOGGER.atInfo().log("Registering ECS systems...");

        // Register WanderSystem
        getEntityStoreRegistry().registerSystem(new WanderSystem());

        LOGGER.atInfo().log("  - Registered WanderSystem");
    }

    /**
     * Initializes the storage and service layers.
     */
    private void initializeServices() {
        LOGGER.atInfo().log("Initializing services...");

        // Get server run directory
        Path serverDirectory = Paths.get("").toAbsolutePath();

        // Initialize storage
        colonyStorage = new ColonyStorage(serverDirectory);

        // Initialize and load colony service
        colonyService = new ColonyService(colonyStorage);
        colonyService.initialize();
    }

    /**
     * Registers plugin commands.
     */
    private void registerCommands() {
        LOGGER.atInfo().log("Registering commands...");

        // /colony command - create and manage colonies
        getCommandRegistry().registerCommand(
                new ColonyCommand(colonyService)
        );

        // /citizen command - manage citizens
        getCommandRegistry().registerCommand(
                new CitizenCommand(colonyService)
        );
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

        if (colonyService != null) {
            colonyService.shutdown();
        }

        LOGGER.atInfo().log("HyColonies shutdown complete. Goodbye!");
    }

    /**
     * Gets the colony service.
     */
    public ColonyService getColonyService() {
        return colonyService;
    }
}
