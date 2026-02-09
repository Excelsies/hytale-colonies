package com.excelsies.hycolonies.ecs.component;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * ECS Component that stores metabolic/needs data for colony citizens.
 * Tracks hunger, happiness, fatigue, and social needs.
 *
 * <h2>Need Values</h2>
 * All need values range from 0-100:
 * <ul>
 *   <li><b>Hunger:</b> 100 = full, 0 = starving. Decays over time.</li>
 *   <li><b>Happiness:</b> 100 = joyful, 0 = miserable. Affected by various factors.</li>
 *   <li><b>Fatigue:</b> 0 = rested, 100 = exhausted. Increases while working.</li>
 *   <li><b>Social:</b> 100 = socially fulfilled, 0 = lonely. Decays over time.</li>
 * </ul>
 *
 * <h2>Thresholds</h2>
 * When needs cross critical thresholds, the MetabolismSystem adds DesireTag
 * components to indicate the citizen needs attention.
 */
public class NeedsComponent implements Component<EntityStore> {

    private static ComponentType<EntityStore, NeedsComponent> COMPONENT_TYPE;

    // === Critical Thresholds ===
    /** Below this hunger level, citizen is critically hungry */
    public static final float HUNGER_CRITICAL = 20.0f;
    /** Below this happiness level, citizen is unhappy */
    public static final float HAPPINESS_CRITICAL = 20.0f;
    /** Above this fatigue level, citizen is exhausted */
    public static final float FATIGUE_CRITICAL = 80.0f;
    /** Below this social level, citizen is lonely */
    public static final float SOCIAL_CRITICAL = 20.0f;

    // === Warning Thresholds (for UI/planning) ===
    /** Below this hunger level, citizen should seek food soon */
    public static final float HUNGER_WARNING = 40.0f;
    /** Below this happiness level, citizen morale is declining */
    public static final float HAPPINESS_WARNING = 40.0f;
    /** Above this fatigue level, citizen should rest soon */
    public static final float FATIGUE_WARNING = 60.0f;
    /** Below this social level, citizen wants social interaction */
    public static final float SOCIAL_WARNING = 40.0f;

    // === Decay Rates (per minute) ===
    /** Hunger decay per minute (at normal activity) */
    public static final float HUNGER_DECAY_RATE = 1.0f;
    /** Social decay per minute (when isolated) */
    public static final float SOCIAL_DECAY_RATE = 0.5f;
    /** Fatigue increase per minute (while working) */
    public static final float FATIGUE_WORK_RATE = 2.0f;
    /** Fatigue decrease per minute (while resting) */
    public static final float FATIGUE_REST_RATE = 5.0f;

    // === Default Values ===
    public static final float DEFAULT_HUNGER = 100.0f;
    public static final float DEFAULT_HAPPINESS = 75.0f;
    public static final float DEFAULT_FATIGUE = 0.0f;
    public static final float DEFAULT_SOCIAL = 50.0f;

    public static final BuilderCodec<NeedsComponent> CODEC = BuilderCodec.builder(NeedsComponent.class, NeedsComponent::new)
            .append(new KeyedCodec<>("Hunger", Codec.STRING),
                    (c, v, i) -> c.hunger = v != null && !v.isEmpty() ? Float.parseFloat(v) : DEFAULT_HUNGER,
                    (c, i) -> String.valueOf(c.hunger))
            .add()
            .append(new KeyedCodec<>("Happiness", Codec.STRING),
                    (c, v, i) -> c.happiness = v != null && !v.isEmpty() ? Float.parseFloat(v) : DEFAULT_HAPPINESS,
                    (c, i) -> String.valueOf(c.happiness))
            .add()
            .append(new KeyedCodec<>("Fatigue", Codec.STRING),
                    (c, v, i) -> c.fatigue = v != null && !v.isEmpty() ? Float.parseFloat(v) : DEFAULT_FATIGUE,
                    (c, i) -> String.valueOf(c.fatigue))
            .add()
            .append(new KeyedCodec<>("Social", Codec.STRING),
                    (c, v, i) -> c.social = v != null && !v.isEmpty() ? Float.parseFloat(v) : DEFAULT_SOCIAL,
                    (c, i) -> String.valueOf(c.social))
            .add()
            .append(new KeyedCodec<>("LastUpdateTime", Codec.STRING),
                    (c, v, i) -> c.lastUpdateTime = v != null && !v.isEmpty() ? Long.parseLong(v) : System.currentTimeMillis(),
                    (c, i) -> String.valueOf(c.lastUpdateTime))
            .add()
            .build();

    private float hunger;
    private float happiness;
    private float fatigue;
    private float social;
    private long lastUpdateTime;

    /**
     * Default constructor for deserialization.
     * Creates a citizen with default starting needs.
     */
    public NeedsComponent() {
        this.hunger = DEFAULT_HUNGER;
        this.happiness = DEFAULT_HAPPINESS;
        this.fatigue = DEFAULT_FATIGUE;
        this.social = DEFAULT_SOCIAL;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Full constructor.
     */
    public NeedsComponent(float hunger, float happiness, float fatigue, float social) {
        this.hunger = clamp(hunger);
        this.happiness = clamp(happiness);
        this.fatigue = clamp(fatigue);
        this.social = clamp(social);
        this.lastUpdateTime = System.currentTimeMillis();
    }

    // === Getters ===

    public float getHunger() {
        return hunger;
    }

    public float getHappiness() {
        return happiness;
    }

    public float getFatigue() {
        return fatigue;
    }

    public float getSocial() {
        return social;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    // === Setters with clamping ===

    public void setHunger(float hunger) {
        this.hunger = clamp(hunger);
    }

    public void setHappiness(float happiness) {
        this.happiness = clamp(happiness);
    }

    public void setFatigue(float fatigue) {
        this.fatigue = clamp(fatigue);
    }

    public void setSocial(float social) {
        this.social = clamp(social);
    }

    public void setLastUpdateTime(long time) {
        this.lastUpdateTime = time;
    }

    // === Modification Methods ===

    /**
     * Decreases hunger by the specified amount.
     * @param amount Amount to decrease (positive value)
     */
    public void decreaseHunger(float amount) {
        this.hunger = clamp(this.hunger - amount);
    }

    /**
     * Increases hunger (feeding).
     * @param amount Amount to increase (positive value)
     */
    public void increaseHunger(float amount) {
        this.hunger = clamp(this.hunger + amount);
    }

    /**
     * Modifies happiness by the specified amount.
     * @param delta Amount to change (can be positive or negative)
     */
    public void modifyHappiness(float delta) {
        this.happiness = clamp(this.happiness + delta);
    }

    /**
     * Increases fatigue (from working).
     * @param amount Amount to increase (positive value)
     */
    public void increaseFatigue(float amount) {
        this.fatigue = clamp(this.fatigue + amount);
    }

    /**
     * Decreases fatigue (from resting).
     * @param amount Amount to decrease (positive value)
     */
    public void decreaseFatigue(float amount) {
        this.fatigue = clamp(this.fatigue - amount);
    }

    /**
     * Decreases social need (isolation).
     * @param amount Amount to decrease (positive value)
     */
    public void decreaseSocial(float amount) {
        this.social = clamp(this.social - amount);
    }

    /**
     * Increases social need (interaction).
     * @param amount Amount to increase (positive value)
     */
    public void increaseSocial(float amount) {
        this.social = clamp(this.social + amount);
    }

    // === Status Checks ===

    /**
     * Returns true if hunger is at critical level.
     */
    public boolean isHungry() {
        return hunger < HUNGER_CRITICAL;
    }

    /**
     * Returns true if hunger is at warning level.
     */
    public boolean isGettingHungry() {
        return hunger < HUNGER_WARNING;
    }

    /**
     * Returns true if happiness is at critical level.
     */
    public boolean isUnhappy() {
        return happiness < HAPPINESS_CRITICAL;
    }

    /**
     * Returns true if fatigue is at critical level.
     */
    public boolean isExhausted() {
        return fatigue > FATIGUE_CRITICAL;
    }

    /**
     * Returns true if fatigue is at warning level.
     */
    public boolean isTired() {
        return fatigue > FATIGUE_WARNING;
    }

    /**
     * Returns true if social is at critical level.
     */
    public boolean isLonely() {
        return social < SOCIAL_CRITICAL;
    }

    /**
     * Returns true if any need is at critical level.
     */
    public boolean hasCriticalNeed() {
        return isHungry() || isUnhappy() || isExhausted() || isLonely();
    }

    /**
     * Returns the most critical need (for prioritization).
     * Returns null if no needs are critical.
     */
    public String getMostCriticalNeed() {
        // Priority: Hunger > Fatigue > Social > Happiness
        if (isHungry()) return "HUNGER";
        if (isExhausted()) return "FATIGUE";
        if (isLonely()) return "SOCIAL";
        if (isUnhappy()) return "HAPPINESS";
        return null;
    }

    // === Utility ===

    /**
     * Clamps a value to the valid range [0, 100].
     */
    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(100.0f, value));
    }

    // === Component Type Management ===

    public static ComponentType<EntityStore, NeedsComponent> getComponentType() {
        return COMPONENT_TYPE;
    }

    public static void setComponentType(ComponentType<EntityStore, NeedsComponent> type) {
        COMPONENT_TYPE = type;
    }

    @Override
    public Component<EntityStore> clone() {
        NeedsComponent clone = new NeedsComponent(hunger, happiness, fatigue, social);
        clone.lastUpdateTime = this.lastUpdateTime;
        return clone;
    }

    @Override
    public String toString() {
        return String.format("NeedsComponent[hunger=%.1f, happiness=%.1f, fatigue=%.1f, social=%.1f]",
                hunger, happiness, fatigue, social);
    }
}
