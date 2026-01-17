package com.excelsies.hycolonies.colony.service;

import com.excelsies.hycolonies.colony.model.CitizenData;
import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.storage.ColonyStorage;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service layer that manages the lifecycle of colonies.
 * Provides an in-memory cache layer on top of ColonyStorage for fast access.
 */
public class ColonyService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int AUTO_SAVE_INTERVAL_MINUTES = 5;

    private final ColonyStorage storage;
    private final Map<UUID, ColonyData> loadedColonies;
    private final Map<UUID, UUID> playerColonyMap;  // Player UUID -> Colony UUID

    /**
     * Creates a new ColonyService.
     *
     * @param storage The storage layer for persistence
     */
    public ColonyService(ColonyStorage storage) {
        this.storage = storage;
        this.loadedColonies = new ConcurrentHashMap<>();
        this.playerColonyMap = new ConcurrentHashMap<>();
    }

    /**
     * Initializes the service by loading all colonies from disk.
     */
    public void initialize() {
        List<ColonyData> colonies = storage.loadAll();
        for (ColonyData colony : colonies) {
            loadedColonies.put(colony.getColonyId(), colony);
            if (colony.getOwnerUuid() != null) {
                playerColonyMap.put(colony.getOwnerUuid(), colony.getColonyId());
            }
        }
        LOGGER.atInfo().log("ColonyService initialized with " + colonies.size() + " colonies");
    }

    /**
     * Schedules the auto-save task.
     *
     * @param executor The executor service to schedule on
     */
    public void startAutoSave(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(
                this::saveAll,
                AUTO_SAVE_INTERVAL_MINUTES,
                AUTO_SAVE_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        LOGGER.atInfo().log("Auto-save scheduled every " + AUTO_SAVE_INTERVAL_MINUTES + " minutes");
    }

    /**
     * Creates a new colony for a player.
     *
     * @param name      The colony name
     * @param ownerUuid The UUID of the owning player
     * @param x         Center X coordinate
     * @param y         Center Y coordinate
     * @param z         Center Z coordinate
     * @param worldId   The world ID
     * @return The created colony, or null if the player already has a colony
     */
    public ColonyData createColony(String name, UUID ownerUuid,
                                   double x, double y, double z, String worldId) {
        // Check if player already has a colony
        if (playerColonyMap.containsKey(ownerUuid)) {
            LOGGER.atWarning().log("Player " + ownerUuid + " already has a colony");
            return null;
        }

        ColonyData colony = new ColonyData(
                UUID.randomUUID(),
                name,
                ownerUuid,
                x, y, z,
                worldId
        );

        loadedColonies.put(colony.getColonyId(), colony);
        playerColonyMap.put(ownerUuid, colony.getColonyId());

        // Save immediately
        storage.save(colony);

        LOGGER.atInfo().log("Created colony '" + name + "' for player " + ownerUuid);
        return colony;
    }

    /**
     * Gets a colony by its ID.
     *
     * @param colonyId The colony UUID
     * @return Optional containing the colony, or empty if not found
     */
    public Optional<ColonyData> getColony(UUID colonyId) {
        return Optional.ofNullable(loadedColonies.get(colonyId));
    }

    /**
     * Gets a player's colony.
     *
     * @param playerUuid The player's UUID
     * @return Optional containing the colony, or empty if the player has no colony
     */
    public Optional<ColonyData> getPlayerColony(UUID playerUuid) {
        UUID colonyId = playerColonyMap.get(playerUuid);
        if (colonyId == null) {
            return Optional.empty();
        }
        return getColony(colonyId);
    }

    /**
     * Checks if a player has a colony.
     *
     * @param playerUuid The player's UUID
     * @return true if the player has a colony
     */
    public boolean hasColony(UUID playerUuid) {
        return playerColonyMap.containsKey(playerUuid);
    }

    /**
     * Adds a citizen to a colony (data only - no in-world entity).
     *
     * @param colonyId The colony UUID
     * @param name     The citizen's name
     * @param x        Position X coordinate
     * @param y        Position Y coordinate
     * @param z        Position Z coordinate
     * @return The created citizen data, or null if the colony wasn't found
     */
    public CitizenData addCitizen(UUID colonyId, String name, double x, double y, double z) {
        ColonyData colony = loadedColonies.get(colonyId);
        if (colony == null) {
            LOGGER.atWarning().log("Cannot add citizen - colony not found: " + colonyId);
            return null;
        }

        CitizenData citizen = new CitizenData(
                UUID.randomUUID(),
                name,
                "default_citizen",
                x, y, z
        );

        colony.addCitizen(citizen);
        LOGGER.atInfo().log("Added citizen '" + name + "' to colony '" + colony.getName() + "'");

        return citizen;
    }

    /**
     * Removes a citizen from a colony.
     *
     * @param colonyId  The colony UUID
     * @param citizenId The citizen UUID to remove
     * @return true if the citizen was removed
     */
    public boolean removeCitizen(UUID colonyId, UUID citizenId) {
        ColonyData colony = loadedColonies.get(colonyId);
        if (colony == null) {
            return false;
        }

        CitizenData existing = colony.getCitizen(citizenId);
        if (existing != null) {
            colony.removeCitizen(citizenId);
            LOGGER.atInfo().log("Removed citizen '" + existing.getName() + "' from colony '" + colony.getName() + "'");
            return true;
        }
        return false;
    }

    /**
     * Saves all loaded colonies to disk.
     */
    public void saveAll() {
        LOGGER.atInfo().log("Saving all colonies...");
        int saved = 0;
        for (ColonyData colony : loadedColonies.values()) {
            if (storage.save(colony)) {
                saved++;
            }
        }
        LOGGER.atInfo().log("Saved " + saved + "/" + loadedColonies.size() + " colonies");
    }

    /**
     * Saves a specific colony immediately.
     *
     * @param colonyId The colony UUID to save
     * @return true if save was successful
     */
    public boolean saveColony(UUID colonyId) {
        ColonyData colony = loadedColonies.get(colonyId);
        if (colony == null) {
            return false;
        }
        return storage.save(colony);
    }

    /**
     * Gets all loaded colonies.
     *
     * @return Unmodifiable collection of all colonies
     */
    public Collection<ColonyData> getAllColonies() {
        return Collections.unmodifiableCollection(loadedColonies.values());
    }

    /**
     * Gets the total number of loaded colonies.
     */
    public int getColonyCount() {
        return loadedColonies.size();
    }

    /**
     * Shuts down the service, saving all data.
     */
    public void shutdown() {
        LOGGER.atInfo().log("Shutting down ColonyService...");
        saveAll();
        loadedColonies.clear();
        playerColonyMap.clear();
        LOGGER.atInfo().log("ColonyService shutdown complete");
    }
}
