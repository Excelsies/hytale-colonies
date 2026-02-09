package com.excelsies.hycolonies.logistics.model;

/**
 * State machine states for Courier job NPCs.
 */
public enum CourierState {
    /**
     * Waiting for a transport task assignment.
     */
    IDLE,

    /**
     * Moving toward the source warehouse to pick up items.
     */
    MOVING_TO_SOURCE,

    /**
     * At source warehouse, picking up items.
     */
    PICKING_UP,

    /**
     * Moving toward the destination with items.
     */
    MOVING_TO_DEST,

    /**
     * At destination, depositing items.
     */
    DEPOSITING,

    /**
     * Returning to idle position or hub after delivery.
     */
    RETURNING;

    /**
     * Parses a state from string, defaulting to IDLE if invalid.
     */
    public static CourierState fromString(String state) {
        if (state == null) return IDLE;
        try {
            return valueOf(state.toUpperCase());
        } catch (IllegalArgumentException e) {
            return IDLE;
        }
    }

    /**
     * Gets the next state in the typical workflow.
     */
    public CourierState getNextState() {
        return switch (this) {
            case IDLE -> MOVING_TO_SOURCE;
            case MOVING_TO_SOURCE -> PICKING_UP;
            case PICKING_UP -> MOVING_TO_DEST;
            case MOVING_TO_DEST -> DEPOSITING;
            case DEPOSITING -> RETURNING;
            case RETURNING -> IDLE;
        };
    }

    /**
     * Returns true if this state involves movement.
     */
    public boolean isMovingState() {
        return this == MOVING_TO_SOURCE || this == MOVING_TO_DEST || this == RETURNING;
    }

    /**
     * Returns true if this state involves interaction with a container.
     */
    public boolean isInteractionState() {
        return this == PICKING_UP || this == DEPOSITING;
    }
}
