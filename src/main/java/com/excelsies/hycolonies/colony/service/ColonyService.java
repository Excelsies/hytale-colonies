package com.excelsies.hycolonies.colony.service;

import com.excelsies.hycolonies.colony.model.CitizenData;
import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.storage.ColonyStorage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service layer that manages the lifecycle of colonies.
 * Provides an in-memory cache layer on top of ColonyStorage for fast access.
 *
 * Note: NPC entity spawning is handled separately in the command layer
 * to ensure proper thread safety (must run on world thread).
 */
public class ColonyService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int AUTO_SAVE_INTERVAL_MINUTES = 5;

    private final ColonyStorage storage;
    private final Map<UUID, ColonyData> loadedColonies;
    private final Map<UUID, UUID> playerColonyMap;  // Player UUID -> Colony UUID
    private final Map<UUID, Ref<EntityStore>> citizenEntityMap;  // Citizen UUID -> Entity Ref
    private final Map<UUID, World> citizenWorldMap;  // Citizen UUID -> World (for entity removal)

    // Pending entity removals that need to be processed on their world threads
    private final List<PendingEntityRemoval> pendingEntityRemovals;

    /**
     * Creates a new ColonyService.
     *
     * @param storage The storage layer for persistence
     */
    public ColonyService(ColonyStorage storage) {
        this.storage = storage;
        this.loadedColonies = new ConcurrentHashMap<>();
        this.playerColonyMap = new ConcurrentHashMap<>();
        this.citizenEntityMap = new ConcurrentHashMap<>();
        this.citizenWorldMap = new ConcurrentHashMap<>();
        this.pendingEntityRemovals = Collections.synchronizedList(new ArrayList<>());
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
        LOGGER.atInfo().log("ColonyService initialized with %d colonies", colonies.size());
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
        LOGGER.atInfo().log("Auto-save scheduled every %d minutes", AUTO_SAVE_INTERVAL_MINUTES);
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
     * @param faction   The faction of the colony
     * @return The created colony, or null if the player already has a colony
     */
    public ColonyData createColony(String name, UUID ownerUuid,
                                   double x, double y, double z, String worldId, com.excelsies.hycolonies.colony.model.Faction faction) {
        if (playerColonyMap.containsKey(ownerUuid)) {
            LOGGER.atWarning().log("Player %s already has a colony", ownerUuid);
            return null;
        }

        ColonyData colony = new ColonyData(
                UUID.randomUUID(),
                name,
                ownerUuid,
                x, y, z,
                worldId,
                faction
        );

        loadedColonies.put(colony.getColonyId(), colony);
        playerColonyMap.put(ownerUuid, colony.getColonyId());

        storage.save(colony);

        LOGGER.atInfo().log("Created colony '%s' for player %s with faction %s", name, ownerUuid, faction);
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
     * Finds colonies by name (case-insensitive).
     *
     * @param name The colony name to search for
     * @return List of colonies matching the name
     */
    public List<ColonyData> getColoniesByName(String name) {
        return loadedColonies.values().stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .toList();
    }

    /**
     * Resolves a colony identifier (UUID string or name).
     * If the identifier is a valid UUID, looks up by UUID.
     * If not, looks up by name.
     *
     * @param identifier The UUID string or colony name
     * @return ResolveResult containing the colony, multiple matches, or not found
     */
    public ColonyResolveResult resolveColony(String identifier) {
        // Try to parse as UUID first
        try {
            UUID uuid = UUID.fromString(identifier);
            Optional<ColonyData> colony = getColony(uuid);
            if (colony.isPresent()) {
                return ColonyResolveResult.found(colony.get());
            }
            return ColonyResolveResult.notFound();
        } catch (IllegalArgumentException e) {
            // Not a UUID, try name lookup
            List<ColonyData> matches = getColoniesByName(identifier);
            if (matches.isEmpty()) {
                return ColonyResolveResult.notFound();
            } else if (matches.size() == 1) {
                return ColonyResolveResult.found(matches.get(0));
            } else {
                return ColonyResolveResult.multiple(matches);
            }
        }
    }

    /**
     * Finds citizens by name within a colony (case-insensitive).
     *
     * @param colonyId The colony UUID
     * @param name     The citizen name to search for
     * @return List of citizens matching the name
     */
    public List<CitizenData> getCitizensByName(UUID colonyId, String name) {
        ColonyData colony = loadedColonies.get(colonyId);
        if (colony == null) {
            return List.of();
        }
        return colony.getCitizens().stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .toList();
    }

    /**
     * Resolves a citizen identifier (UUID string or name) within a colony.
     *
     * @param colonyId   The colony UUID
     * @param identifier The UUID string or citizen name
     * @return CitizenResolveResult containing the citizen, multiple matches, or not found
     */
    public CitizenResolveResult resolveCitizen(UUID colonyId, String identifier) {
        ColonyData colony = loadedColonies.get(colonyId);
        if (colony == null) {
            return CitizenResolveResult.notFound();
        }

        // Try to parse as UUID first
        try {
            UUID uuid = UUID.fromString(identifier);
            CitizenData citizen = colony.getCitizen(uuid);
            if (citizen != null) {
                return CitizenResolveResult.found(citizen);
            }
            return CitizenResolveResult.notFound();
        } catch (IllegalArgumentException e) {
            // Not a UUID, try name lookup
            List<CitizenData> matches = getCitizensByName(colonyId, identifier);
            if (matches.isEmpty()) {
                return CitizenResolveResult.notFound();
            } else if (matches.size() == 1) {
                return CitizenResolveResult.found(matches.get(0));
            } else {
                return CitizenResolveResult.multiple(matches);
            }
        }
    }

    /**
     * Result of resolving a colony by name or UUID.
     */
    public static class ColonyResolveResult {
        private final ColonyData colony;
        private final List<ColonyData> multipleMatches;
        private final boolean found;

        private ColonyResolveResult(ColonyData colony, List<ColonyData> multipleMatches, boolean found) {
            this.colony = colony;
            this.multipleMatches = multipleMatches;
            this.found = found;
        }

        public static ColonyResolveResult found(ColonyData colony) {
            return new ColonyResolveResult(colony, null, true);
        }

        public static ColonyResolveResult multiple(List<ColonyData> matches) {
            return new ColonyResolveResult(null, matches, false);
        }

        public static ColonyResolveResult notFound() {
            return new ColonyResolveResult(null, null, false);
        }

        public boolean isFound() { return found; }
        public boolean hasMultipleMatches() { return multipleMatches != null && !multipleMatches.isEmpty(); }
        public boolean isNotFound() { return !found && multipleMatches == null; }
        public ColonyData getColony() { return colony; }
        public List<ColonyData> getMultipleMatches() { return multipleMatches; }
    }

    /**
     * Result of resolving a citizen by name or UUID.
     */
    public static class CitizenResolveResult {
        private final CitizenData citizen;
        private final List<CitizenData> multipleMatches;
        private final boolean found;

        private CitizenResolveResult(CitizenData citizen, List<CitizenData> multipleMatches, boolean found) {
            this.citizen = citizen;
            this.multipleMatches = multipleMatches;
            this.found = found;
        }

        public static CitizenResolveResult found(CitizenData citizen) {
            return new CitizenResolveResult(citizen, null, true);
        }

        public static CitizenResolveResult multiple(List<CitizenData> matches) {
            return new CitizenResolveResult(null, matches, false);
        }

        public static CitizenResolveResult notFound() {
            return new CitizenResolveResult(null, null, false);
        }

        public boolean isFound() { return found; }
        public boolean hasMultipleMatches() { return multipleMatches != null && !multipleMatches.isEmpty(); }
        public boolean isNotFound() { return !found && multipleMatches == null; }
        public CitizenData getCitizen() { return citizen; }
        public List<CitizenData> getMultipleMatches() { return multipleMatches; }
    }

    /**
     * Adds a citizen to a colony (data only - no in-world entity).
     * Entity spawning should be handled separately via CitizenCommand.
     *
     * @param colonyId The colony UUID
     * @param name     The citizen's name
     * @param x        Position X coordinate
     * @param y        Position Y coordinate
     * @param z        Position Z coordinate
     * @param npcSkin  The NPC skin/type (optional, can be null to auto-select based on faction)
     * @return The created citizen data, or null if the colony wasn't found
     */
    public CitizenData addCitizen(UUID colonyId, String name, double x, double y, double z, String npcSkin) {
        return addCitizen(colonyId, name, x, y, z, npcSkin, false);
    }

    /**
     * Adds a citizen to a colony.
     *
     * @param colonyId    The colony UUID
     * @param name        The citizen's name
     * @param x           Position X coordinate
     * @param y           Position Y coordinate
     * @param z           Position Z coordinate
     * @param npcSkin     The NPC skin/type (optional, can be null to auto-select based on faction)
     * @param spawnEntity Ignored - entity spawning must be done via command layer
     * @return The created citizen data, or null if the colony wasn't found
     */
    public CitizenData addCitizen(UUID colonyId, String name, double x, double y, double z, String npcSkin, boolean spawnEntity) {
        ColonyData colony = loadedColonies.get(colonyId);
        if (colony == null) {
            LOGGER.atWarning().log("Cannot add citizen - colony not found: %s", colonyId);
            return null;
        }

        if (npcSkin == null) {
            npcSkin = colony.getFaction().getRandomSkin();
        }

        CitizenData citizen = new CitizenData(
                UUID.randomUUID(),
                name,
                npcSkin,
                x, y, z
        );

        colony.addCitizen(citizen);
        LOGGER.atInfo().log("Added citizen '%s' (%s) to colony '%s'", name, npcSkin, colony.getName());

        return citizen;
    }

    /**
     * Removes a citizen from a colony.
     * Note: This method removes the citizen data but does NOT despawn the entity.
     * Use removeCitizenWithEntity() to also remove the in-world entity.
     *
     * @param colonyId  The colony UUID
     * @param citizenId The citizen UUID to remove
     * @return The removed citizen data, or null if not found
     */
    public CitizenData removeCitizen(UUID colonyId, UUID citizenId) {
        ColonyData colony = loadedColonies.get(colonyId);
        if (colony == null) {
            return null;
        }

        CitizenData existing = colony.getCitizen(citizenId);
        if (existing != null) {
            colony.removeCitizen(citizenId);
            LOGGER.atInfo().log("Removed citizen '%s' from colony '%s'", existing.getName(), colony.getName());
            return existing;
        }
        return null;
    }

    /**
     * Removes a citizen from a colony AND despawns their in-world entity.
     * The entity removal will be queued and executed on the appropriate world thread.
     *
     * @param colonyId  The colony UUID
     * @param citizenId The citizen UUID to remove
     * @return The removed citizen data, or null if not found
     */
    public CitizenData removeCitizenWithEntity(UUID colonyId, UUID citizenId) {
        CitizenData removed = removeCitizen(colonyId, citizenId);
        if (removed != null) {
            queueEntityRemoval(citizenId);
        }
        return removed;
    }

    /**
     * Queues an entity for removal on the appropriate world thread.
     *
     * @param citizenId The citizen UUID whose entity should be removed
     */
    private void queueEntityRemoval(UUID citizenId) {
        Ref<EntityStore> entityRef = citizenEntityMap.get(citizenId);
        World world = citizenWorldMap.get(citizenId);

        if (entityRef == null || world == null) {
            // Entity not spawned, just clean up tracking
            unregisterCitizenEntity(citizenId);
            return;
        }

        // Queue the removal to be processed
        pendingEntityRemovals.add(new PendingEntityRemoval(citizenId, entityRef, world));
        LOGGER.atFine().log("Queued entity removal for citizen %s", citizenId.toString().substring(0, 8));
    }

    /**
     * Processes all pending entity removals.
     * Call this periodically or when appropriate to execute queued removals.
     */
    public void processPendingEntityRemovals() {
        if (pendingEntityRemovals.isEmpty()) {
            return;
        }

        List<PendingEntityRemoval> toProcess;
        synchronized (pendingEntityRemovals) {
            toProcess = new ArrayList<>(pendingEntityRemovals);
            pendingEntityRemovals.clear();
        }

        for (PendingEntityRemoval removal : toProcess) {
            removal.world.execute(() -> {
                try {
                    Store<EntityStore> store = removal.entityRef.getStore();
                    if (store != null) {
                        store.removeEntity(removal.entityRef, RemoveReason.REMOVE);
                        LOGGER.atInfo().log("Despawned entity for citizen %s",
                                removal.citizenId.toString().substring(0, 8));
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().withCause(e).log("Failed to despawn entity for citizen %s",
                            removal.citizenId.toString().substring(0, 8));
                } finally {
                    // Clean up tracking regardless of success
                    citizenEntityMap.remove(removal.citizenId);
                    citizenWorldMap.remove(removal.citizenId);
                }
            });
        }
    }

    /**
     * Immediately removes a citizen entity on the current thread.
     * MUST be called from the world thread!
     *
     * @param citizenId The citizen UUID
     * @param store The entity store (must be from the same world)
     */
    public void despawnCitizenEntityImmediate(UUID citizenId, Store<EntityStore> store) {
        Ref<EntityStore> entityRef = citizenEntityMap.get(citizenId);
        if (entityRef == null) {
            return;
        }

        try {
            store.removeEntity(entityRef, RemoveReason.REMOVE);
            LOGGER.atInfo().log("Despawned entity for citizen %s", citizenId.toString().substring(0, 8));
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Failed to despawn entity for citizen %s",
                    citizenId.toString().substring(0, 8));
        } finally {
            unregisterCitizenEntity(citizenId);
        }
    }

    /**
     * Represents a pending entity removal operation.
     */
    private record PendingEntityRemoval(UUID citizenId, Ref<EntityStore> entityRef, World world) {}

    /**
     * Deletes a colony and all its citizens.
     *
     * @param colonyId The colony UUID to delete
     * @return The deleted colony data, or null if not found
     */
    public ColonyData deleteColony(UUID colonyId) {
        ColonyData colony = loadedColonies.remove(colonyId);
        if (colony == null) {
            LOGGER.atWarning().log("Cannot delete colony - not found: %s", colonyId);
            return null;
        }

        // Remove player mapping if this colony had an owner
        if (colony.getOwnerUuid() != null) {
            playerColonyMap.remove(colony.getOwnerUuid());
        }

        // Delete from storage
        storage.delete(colonyId);

        LOGGER.atInfo().log("Deleted colony '%s' with %d citizens", colony.getName(), colony.getPopulation());
        return colony;
    }

    /**
     * Saves all loaded colonies to disk.
     * Attempts to sync citizen positions before saving.
     */
    public void saveAll() {
        LOGGER.atInfo().log("Saving all colonies...");

        // Sync citizen positions before save
        syncAllCitizenPositions();

        int saved = 0;
        for (ColonyData colony : loadedColonies.values()) {
            if (storage.save(colony)) {
                saved++;
            }
        }
        LOGGER.atInfo().log("Saved %d/%d colonies", saved, loadedColonies.size());
    }

    /**
     * Synchronizes all spawned citizen entity positions to their CitizenData.
     * This updates last known positions before persistence.
     * Note: This schedules position syncs on each world thread.
     */
    public void syncAllCitizenPositions() {
        if (citizenEntityMap.isEmpty()) {
            return;
        }

        // Group citizens by world for efficient batch processing
        Map<World, Set<UUID>> citizensByWorld = new HashMap<>();
        for (Map.Entry<UUID, World> entry : citizenWorldMap.entrySet()) {
            UUID citizenId = entry.getKey();
            World world = entry.getValue();
            if (world != null && citizenEntityMap.containsKey(citizenId)) {
                citizensByWorld.computeIfAbsent(world, k -> new HashSet<>()).add(citizenId);
            }
        }

        AtomicInteger synced = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        // Schedule sync on each world thread
        for (Map.Entry<World, Set<UUID>> entry : citizensByWorld.entrySet()) {
            World world = entry.getKey();
            Set<UUID> citizenIds = entry.getValue();

            world.execute(() -> {
                Store<EntityStore> store = world.getEntityStore().getStore();
                if (store == null) return;

                for (UUID citizenId : citizenIds) {
                    if (syncCitizenPositionInternal(citizenId, store)) {
                        synced.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                }
            });
        }

        LOGGER.atFine().log("Scheduled position sync for %d citizens across %d worlds",
                citizenEntityMap.size(), citizensByWorld.size());
    }

    /**
     * Synchronizes a single citizen's position from their entity to CitizenData.
     * Must be called from the world thread.
     *
     * @param citizenId The citizen UUID
     * @param store     The entity store
     * @return true if sync was successful
     */
    public boolean syncCitizenPosition(UUID citizenId, Store<EntityStore> store) {
        return syncCitizenPositionInternal(citizenId, store);
    }

    /**
     * Internal method to sync a citizen's position.
     */
    private boolean syncCitizenPositionInternal(UUID citizenId, Store<EntityStore> store) {
        Ref<EntityStore> entityRef = citizenEntityMap.get(citizenId);
        if (entityRef == null) {
            return false;
        }

        try {
            TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
            if (transform == null || transform.getPosition() == null) {
                return false;
            }

            Vector3d position = transform.getPosition();

            // Find the citizen data and update it
            for (ColonyData colony : loadedColonies.values()) {
                CitizenData citizen = colony.getCitizen(citizenId);
                if (citizen != null) {
                    citizen.updateLastPosition(position.getX(), position.getY(), position.getZ());
                    LOGGER.atFine().log("Synced position for citizen %s: (%.1f, %.1f, %.1f)",
                            citizenId.toString().substring(0, 8),
                            position.getX(), position.getY(), position.getZ());
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.atFine().log("Failed to sync position for citizen %s: %s",
                    citizenId.toString().substring(0, 8), e.getMessage());
        }
        return false;
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

    // === Entity Reference Tracking ===

    /**
     * Registers an entity reference for a citizen.
     * Called when a citizen NPC is spawned in the world.
     *
     * @param citizenId The citizen UUID
     * @param entityRef The entity reference
     * @param world     The world the entity is in
     */
    public void registerCitizenEntity(UUID citizenId, Ref<EntityStore> entityRef, World world) {
        citizenEntityMap.put(citizenId, entityRef);
        if (world != null) {
            citizenWorldMap.put(citizenId, world);
        }
        LOGGER.atFine().log("Registered entity for citizen %s", citizenId.toString().substring(0, 8));
    }

    /**
     * Registers an entity reference for a citizen (without world tracking).
     * @deprecated Use {@link #registerCitizenEntity(UUID, Ref, World)} instead
     */
    @Deprecated
    public void registerCitizenEntity(UUID citizenId, Ref<EntityStore> entityRef) {
        registerCitizenEntity(citizenId, entityRef, null);
    }

    /**
     * Unregisters an entity reference for a citizen.
     * Called when a citizen NPC is despawned or removed.
     *
     * @param citizenId The citizen UUID
     */
    public void unregisterCitizenEntity(UUID citizenId) {
        citizenEntityMap.remove(citizenId);
        citizenWorldMap.remove(citizenId);
        LOGGER.atFine().log("Unregistered entity for citizen %s", citizenId.toString().substring(0, 8));
    }

    /**
     * Gets the entity reference for a citizen.
     *
     * @param citizenId The citizen UUID
     * @return The entity reference, or null if not spawned
     */
    public Ref<EntityStore> getCitizenEntity(UUID citizenId) {
        return citizenEntityMap.get(citizenId);
    }

    /**
     * Checks if a citizen has a spawned entity.
     *
     * @param citizenId The citizen UUID
     * @return true if the citizen has a registered entity
     */
    public boolean isCitizenSpawned(UUID citizenId) {
        return citizenEntityMap.containsKey(citizenId);
    }

    /**
     * Gets the number of spawned citizen entities.
     */
    public int getSpawnedCitizenCount() {
        return citizenEntityMap.size();
    }

    /**
     * Shuts down the service, saving all data.
     * Syncs citizen positions before final save.
     */
    public void shutdown() {
        LOGGER.atInfo().log("Shutting down ColonyService...");

        // Process any pending entity removals
        processPendingEntityRemovals();

        // Save all data (includes position sync)
        saveAll();

        // Clear all in-memory data
        loadedColonies.clear();
        playerColonyMap.clear();
        citizenEntityMap.clear();
        citizenWorldMap.clear();

        LOGGER.atInfo().log("ColonyService shutdown complete");
    }
}
