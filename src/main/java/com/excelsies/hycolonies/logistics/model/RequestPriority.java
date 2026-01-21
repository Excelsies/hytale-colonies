package com.excelsies.hycolonies.logistics.model;

/**
 * Priority levels for item requests.
 * Higher priority requests are processed first by the logistics solver.
 */
public enum RequestPriority {
    LOW(0),
    NORMAL(1),
    HIGH(2),
    URGENT(3);  // Starvation, critical building materials

    private final int value;

    RequestPriority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * Parses a priority from string, defaulting to NORMAL if invalid.
     */
    public static RequestPriority fromString(String str) {
        if (str == null) return NORMAL;
        try {
            return valueOf(str.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}
