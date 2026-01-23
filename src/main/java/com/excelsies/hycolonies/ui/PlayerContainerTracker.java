package com.excelsies.hycolonies.ui;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which container each player currently has open.
 *
 * <p>This allows the /warehouse toggle command to know which container
 * to act on without requiring the player to specify coordinates.
 */
public class PlayerContainerTracker {

    /**
     * Data about a container a player is currently viewing.
     */
    public record ViewingContainer(
            UUID colonyId,
            String colonyName,
            Vector3i position,
            World world,
            int capacity,
            boolean isRegistered
    ) {}

    private final Map<UUID, ViewingContainer> playerContainers = new ConcurrentHashMap<>();

    /**
     * Records that a player is viewing a container.
     *
     * @param playerUuid The player's UUID
     * @param container The container data
     */
    public void setViewingContainer(UUID playerUuid, ViewingContainer container) {
        playerContainers.put(playerUuid, container);
    }

    /**
     * Gets the container a player is currently viewing.
     *
     * @param playerUuid The player's UUID
     * @return The container data, or null if not viewing any container
     */
    @Nullable
    public ViewingContainer getViewingContainer(UUID playerUuid) {
        return playerContainers.get(playerUuid);
    }

    /**
     * Clears the container tracking for a player.
     *
     * @param playerUuid The player's UUID
     */
    public void clearViewingContainer(UUID playerUuid) {
        playerContainers.remove(playerUuid);
    }

    /**
     * Updates the registration status for a player's currently viewed container.
     *
     * @param playerUuid The player's UUID
     * @param isRegistered The new registration status
     */
    public void updateRegistrationStatus(UUID playerUuid, boolean isRegistered) {
        ViewingContainer current = playerContainers.get(playerUuid);
        if (current != null) {
            playerContainers.put(playerUuid, new ViewingContainer(
                    current.colonyId(),
                    current.colonyName(),
                    current.position(),
                    current.world(),
                    current.capacity(),
                    isRegistered
            ));
        }
    }
}
