package com.excelsies.hycolonies.ecs.component;

/**
 * Types of jobs that colony citizens can perform.
 */
public enum JobType {
    /**
     * No assigned job - citizen will wander or perform basic tasks.
     */
    UNEMPLOYED,

    /**
     * Courier job - transports items between warehouses.
     */
    COURIER,

    /**
     * Builder job - constructs buildings from blueprints.
     */
    BUILDER,

    /**
     * Miner job - extracts resources from the world.
     */
    MINER,

    /**
     * Farmer job - manages crops and livestock.
     */
    FARMER,

    /**
     * Guard job - protects the colony from threats.
     */
    GUARD;

    /**
     * Parses a job type from string, defaulting to UNEMPLOYED if invalid.
     */
    public static JobType fromString(String str) {
        if (str == null) return UNEMPLOYED;
        try {
            return valueOf(str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNEMPLOYED;
        }
    }

    /**
     * Returns a user-friendly display name.
     */
    public String getDisplayName() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
