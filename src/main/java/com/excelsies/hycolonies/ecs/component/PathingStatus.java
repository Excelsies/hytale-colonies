package com.excelsies.hycolonies.ecs.component;

/**
 * Status of an NPC's pathfinding state.
 */
public enum PathingStatus {
    /**
     * No path being followed; NPC is stationary or wandering.
     */
    IDLE,

    /**
     * Path is being calculated by the pathfinding system.
     */
    COMPUTING,

    /**
     * Actively following a path toward the target.
     */
    MOVING,

    /**
     * Path was blocked or target is unreachable.
     */
    BLOCKED,

    /**
     * Successfully arrived at the target destination.
     */
    ARRIVED;

    /**
     * Parses a status from string, defaulting to IDLE if invalid.
     */
    public static PathingStatus fromString(String str) {
        if (str == null) return IDLE;
        try {
            return valueOf(str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return IDLE;
        }
    }

    /**
     * Returns true if the NPC is actively moving.
     */
    public boolean isMoving() {
        return this == MOVING;
    }

    /**
     * Returns true if the NPC needs a new path.
     */
    public boolean needsNewPath() {
        return this == IDLE || this == ARRIVED || this == BLOCKED;
    }
}
