package com.excelsies.hycolonies.ecs.tag;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Tag component that indicates a citizen has a critical need that should be addressed.
 * Added by MetabolismSystem when needs fall below critical thresholds.
 * Used by AI systems to interrupt normal work and seek need fulfillment.
 *
 * <h2>DesireType Priority</h2>
 * When multiple needs are critical, the highest priority desire is tracked:
 * <ol>
 *   <li>HUNGRY - Seek food immediately</li>
 *   <li>TIRED - Find a place to rest</li>
 *   <li>LONELY - Seek social interaction</li>
 *   <li>UNHAPPY - General morale issue</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * Systems can query for entities with DesireTag to find citizens that need
 * to interrupt their current task. The JobOrchestratorSystem should check
 * for DesireTag before assigning new work tasks.
 */
public class DesireTag implements Component<EntityStore> {

    /**
     * Types of desires that can trigger behavioral changes.
     */
    public enum DesireType {
        /** Citizen is critically hungry and needs food */
        HUNGRY,
        /** Citizen is exhausted and needs rest */
        TIRED,
        /** Citizen is lonely and needs social interaction */
        LONELY,
        /** Citizen is unhappy (general morale issue) */
        UNHAPPY;

        /**
         * Parses a DesireType from a string.
         * Returns HUNGRY as default if parsing fails.
         */
        public static DesireType fromString(String value) {
            if (value == null || value.isEmpty()) {
                return HUNGRY;
            }
            try {
                return DesireType.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return HUNGRY;
            }
        }
    }

    private static ComponentType<EntityStore, DesireTag> COMPONENT_TYPE;

    public static final BuilderCodec<DesireTag> CODEC = BuilderCodec.builder(DesireTag.class, DesireTag::new)
            .append(new KeyedCodec<>("DesireType", Codec.STRING),
                    (c, v, i) -> c.desireType = DesireType.fromString(v),
                    (c, i) -> c.desireType.name())
            .add()
            .append(new KeyedCodec<>("Severity", Codec.STRING),
                    (c, v, i) -> c.severity = v != null && !v.isEmpty() ? Float.parseFloat(v) : 0.0f,
                    (c, i) -> String.valueOf(c.severity))
            .add()
            .append(new KeyedCodec<>("CreatedTime", Codec.STRING),
                    (c, v, i) -> c.createdTime = v != null && !v.isEmpty() ? Long.parseLong(v) : System.currentTimeMillis(),
                    (c, i) -> String.valueOf(c.createdTime))
            .add()
            .build();

    private DesireType desireType;
    private float severity;
    private long createdTime;

    /**
     * Default constructor for deserialization.
     */
    public DesireTag() {
        this.desireType = DesireType.HUNGRY;
        this.severity = 0.0f;
        this.createdTime = System.currentTimeMillis();
    }

    /**
     * Constructor with desire type.
     * @param desireType The type of desire
     */
    public DesireTag(DesireType desireType) {
        this.desireType = desireType;
        this.severity = 0.0f;
        this.createdTime = System.currentTimeMillis();
    }

    /**
     * Full constructor.
     * @param desireType The type of desire
     * @param severity How severe the need is (0-100, higher = more urgent)
     */
    public DesireTag(DesireType desireType, float severity) {
        this.desireType = desireType;
        this.severity = severity;
        this.createdTime = System.currentTimeMillis();
    }

    // === Getters ===

    public DesireType getDesireType() {
        return desireType;
    }

    public float getSeverity() {
        return severity;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    /**
     * Returns the time in milliseconds since this desire was created.
     */
    public long getTimeSinceCreated() {
        return System.currentTimeMillis() - createdTime;
    }

    // === Setters ===

    public void setDesireType(DesireType desireType) {
        this.desireType = desireType;
    }

    public void setSeverity(float severity) {
        this.severity = severity;
    }

    // === Utility ===

    /**
     * Returns true if this desire is more urgent than another.
     * Urgency is determined by: 1) desire type priority, 2) severity, 3) time waiting
     */
    public boolean isMoreUrgentThan(DesireTag other) {
        if (other == null) return true;

        // Compare by desire type priority first
        int thisPriority = desireType.ordinal();
        int otherPriority = other.desireType.ordinal();
        if (thisPriority != otherPriority) {
            return thisPriority < otherPriority; // Lower ordinal = higher priority
        }

        // Same type - compare by severity
        if (Math.abs(this.severity - other.severity) > 5.0f) {
            return this.severity > other.severity;
        }

        // Similar severity - older desire is more urgent
        return this.createdTime < other.createdTime;
    }

    // === Component Type Management ===

    public static ComponentType<EntityStore, DesireTag> getComponentType() {
        return COMPONENT_TYPE;
    }

    public static void setComponentType(ComponentType<EntityStore, DesireTag> type) {
        COMPONENT_TYPE = type;
    }

    @Override
    public Component<EntityStore> clone() {
        DesireTag clone = new DesireTag(desireType, severity);
        clone.createdTime = this.createdTime;
        return clone;
    }

    @Override
    public String toString() {
        return String.format("DesireTag[type=%s, severity=%.1f]", desireType, severity);
    }
}
