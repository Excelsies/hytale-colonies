package com.excelsies.hycolonies.ecs.system;

import com.excelsies.hycolonies.ecs.component.CitizenComponent;
import com.excelsies.hycolonies.ecs.component.JobComponent;
import com.excelsies.hycolonies.ecs.component.NeedsComponent;
import com.excelsies.hycolonies.ecs.tag.DesireTag;
import com.excelsies.hycolonies.ecs.tag.DesireTag.DesireType;
import com.excelsies.hycolonies.ecs.tag.IdleTag;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ECS System that processes metabolic needs for colony citizens.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Decays hunger over time</li>
 *   <li>Increases fatigue while working, decreases while idle</li>
 *   <li>Decays social need when isolated</li>
 *   <li>Adds DesireTag when needs reach critical thresholds</li>
 *   <li>Removes DesireTag when needs are satisfied</li>
 * </ul>
 *
 * <h2>Tick Rate</h2>
 * Processes every 60 ticks (~3 seconds at 20 TPS) to avoid performance impact.
 * Actual need decay is calculated based on real time elapsed since last update.
 *
 * <h2>Happiness Calculation</h2>
 * Happiness is affected by:
 * <ul>
 *   <li>Having critical hunger/fatigue (decreases)</li>
 *   <li>Social isolation (decreases)</li>
 *   <li>Being well-fed and rested (slowly increases)</li>
 * </ul>
 */
public class MetabolismSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Tick interval for metabolism processing.
     * 60 ticks = ~3 seconds at 20 TPS.
     */
    private static final int METABOLISM_TICK_INTERVAL = 60;

    /**
     * Tracks the last game tick when metabolism was processed.
     */
    private final AtomicLong lastProcessedTick = new AtomicLong(-METABOLISM_TICK_INTERVAL);

    /**
     * Monotonically increasing tick counter.
     */
    private final AtomicLong currentTick = new AtomicLong(0);

    /**
     * Milliseconds per minute (for converting real time to decay amounts).
     */
    private static final float MS_PER_MINUTE = 60000.0f;

    public MetabolismSystem() {
        super();
    }

    @Override
    public Query<EntityStore> getQuery() {
        // Query all entities, filter manually in tick() for NeedsComponent
        return Query.any();
    }

    @Override
    public void tick(float deltaTime, int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        // Skip if components not registered yet
        if (NeedsComponent.getComponentType() == null || CitizenComponent.getComponentType() == null) {
            return;
        }

        // Rate limit: Only process every METABOLISM_TICK_INTERVAL ticks
        // Use index == 0 check combined with atomic counter for global rate limiting
        if (index == 0) {
            long thisTick = currentTick.incrementAndGet();
            long lastTick = lastProcessedTick.get();
            if (thisTick - lastTick < METABOLISM_TICK_INTERVAL) {
                return;
            }
            // Try to claim this tick for processing
            if (!lastProcessedTick.compareAndSet(lastTick, thisTick)) {
                return;
            }
        }

        Ref<EntityStore> ref = chunk.getReferenceTo(index);

        // Only process entities with NeedsComponent (citizens)
        NeedsComponent needs = store.getComponent(ref, NeedsComponent.getComponentType());
        if (needs == null) {
            return;
        }

        // Check if this entity is a citizen (has CitizenComponent)
        CitizenComponent citizen = store.getComponent(ref, CitizenComponent.getComponentType());
        if (citizen == null) {
            return;
        }

        // Process this citizen's metabolism
        processMetabolism(ref, needs, store, commandBuffer);
    }

    /**
     * Processes metabolism for a single citizen.
     * Directly modifies the NeedsComponent (mutable approach).
     */
    private void processMetabolism(Ref<EntityStore> ref, NeedsComponent needs,
                                    Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {

        long currentTime = System.currentTimeMillis();
        long lastUpdate = needs.getLastUpdateTime();
        float minutesElapsed = (currentTime - lastUpdate) / MS_PER_MINUTE;

        // Prevent large jumps if time hasn't advanced or there's a big gap
        if (minutesElapsed <= 0.0f) {
            return;
        }
        if (minutesElapsed > 10.0f) {
            // Cap at 10 minutes to prevent huge changes after server restart
            minutesElapsed = 10.0f;
        }

        // Check if citizen is working or idle
        boolean isWorking = isWorking(ref, store);

        // === Update Hunger ===
        float hungerDecay = NeedsComponent.HUNGER_DECAY_RATE * minutesElapsed;
        if (isWorking) {
            // Working increases hunger decay by 50%
            hungerDecay *= 1.5f;
        }
        needs.decreaseHunger(hungerDecay);

        // === Update Fatigue ===
        if (isWorking) {
            // Fatigue increases while working
            float fatigueIncrease = NeedsComponent.FATIGUE_WORK_RATE * minutesElapsed;
            needs.increaseFatigue(fatigueIncrease);
        } else {
            // Fatigue decreases while idle
            float fatigueDecrease = NeedsComponent.FATIGUE_REST_RATE * minutesElapsed;
            needs.decreaseFatigue(fatigueDecrease);
        }

        // === Update Social ===
        // TODO: In the future, check for nearby citizens to boost social
        // For now, social slowly decays
        float socialDecay = NeedsComponent.SOCIAL_DECAY_RATE * minutesElapsed;
        needs.decreaseSocial(socialDecay);

        // === Update Happiness ===
        float happinessDelta = calculateHappinessDelta(needs, minutesElapsed);
        needs.modifyHappiness(happinessDelta);

        // Update last update time
        needs.setLastUpdateTime(currentTime);

        // === Handle Desire Tags ===
        updateDesireTags(ref, needs, store, commandBuffer);
    }

    /**
     * Checks if a citizen is currently working (has JobComponent and is not idle).
     */
    private boolean isWorking(Ref<EntityStore> ref, Store<EntityStore> store) {
        // Check for IdleTag - if present, not working
        if (IdleTag.getComponentType() != null) {
            IdleTag idleTag = store.getComponent(ref, IdleTag.getComponentType());
            if (idleTag != null) {
                return false;
            }
        }

        // Check JobComponent for assigned task
        if (JobComponent.getComponentType() != null) {
            JobComponent job = store.getComponent(ref, JobComponent.getComponentType());
            if (job != null && !job.isIdle()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Calculates the happiness change based on current needs.
     */
    private float calculateHappinessDelta(NeedsComponent needs, float minutesElapsed) {
        float delta = 0.0f;

        // Hunger affects happiness
        if (needs.isHungry()) {
            // Critical hunger causes rapid happiness decline
            delta -= 2.0f * minutesElapsed;
        } else if (needs.isGettingHungry()) {
            // Warning level causes slight decline
            delta -= 0.5f * minutesElapsed;
        }

        // Fatigue affects happiness
        if (needs.isExhausted()) {
            delta -= 1.5f * minutesElapsed;
        } else if (needs.isTired()) {
            delta -= 0.3f * minutesElapsed;
        }

        // Loneliness affects happiness
        if (needs.isLonely()) {
            delta -= 1.0f * minutesElapsed;
        }

        // If all needs are satisfied, happiness slowly recovers
        if (!needs.hasCriticalNeed() && needs.getHunger() > NeedsComponent.HUNGER_WARNING
                && needs.getFatigue() < NeedsComponent.FATIGUE_WARNING
                && needs.getSocial() > NeedsComponent.SOCIAL_WARNING) {
            delta += 0.5f * minutesElapsed;
        }

        return delta;
    }

    /**
     * Updates DesireTag based on current needs.
     * Adds tag if critical, removes if no longer critical, updates if type changed.
     */
    private void updateDesireTags(Ref<EntityStore> ref, NeedsComponent needs,
                                   Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {

        if (DesireTag.getComponentType() == null) {
            return;
        }

        DesireTag existingTag = store.getComponent(ref, DesireTag.getComponentType());
        boolean hasCriticalNeed = needs.hasCriticalNeed();

        if (hasCriticalNeed) {
            // Determine the most urgent desire
            DesireType desireType = determineDesireType(needs);
            float severity = calculateSeverity(needs, desireType);

            if (existingTag == null) {
                // Add new DesireTag
                DesireTag newTag = new DesireTag(desireType, severity);
                commandBuffer.addComponent(ref, DesireTag.getComponentType(), newTag);
                LOGGER.atFine().log("Citizen gained desire: %s (severity: %.1f)", desireType, severity);
            } else if (existingTag.getDesireType() != desireType) {
                // Update existing tag with new type
                existingTag.setDesireType(desireType);
                existingTag.setSeverity(severity);
                LOGGER.atFine().log("Citizen desire changed to: %s", desireType);
            } else if (Math.abs(existingTag.getSeverity() - severity) > 10.0f) {
                // Update severity if significantly different
                existingTag.setSeverity(severity);
            }
        } else if (existingTag != null) {
            // No critical needs - remove DesireTag
            commandBuffer.removeComponent(ref, DesireTag.getComponentType());
            LOGGER.atFine().log("Citizen desire satisfied: %s", existingTag.getDesireType());
        }
    }

    /**
     * Determines the most urgent DesireType based on needs.
     * Priority: HUNGRY > TIRED > LONELY > UNHAPPY
     */
    private DesireType determineDesireType(NeedsComponent needs) {
        if (needs.isHungry()) {
            return DesireType.HUNGRY;
        }
        if (needs.isExhausted()) {
            return DesireType.TIRED;
        }
        if (needs.isLonely()) {
            return DesireType.LONELY;
        }
        if (needs.isUnhappy()) {
            return DesireType.UNHAPPY;
        }
        // Default (shouldn't reach here if hasCriticalNeed is true)
        return DesireType.HUNGRY;
    }

    /**
     * Calculates severity (0-100) for a desire type.
     * Higher severity = more urgent need.
     */
    private float calculateSeverity(NeedsComponent needs, DesireType type) {
        return switch (type) {
            case HUNGRY -> {
                // Map hunger 0-20 to severity 100-0
                float hunger = needs.getHunger();
                yield 100.0f - (hunger / NeedsComponent.HUNGER_CRITICAL * 100.0f);
            }
            case TIRED -> {
                // Map fatigue 80-100 to severity 0-100
                float fatigue = needs.getFatigue();
                float excess = fatigue - NeedsComponent.FATIGUE_CRITICAL;
                yield (excess / (100.0f - NeedsComponent.FATIGUE_CRITICAL)) * 100.0f;
            }
            case LONELY -> {
                // Map social 0-20 to severity 100-0
                float social = needs.getSocial();
                yield 100.0f - (social / NeedsComponent.SOCIAL_CRITICAL * 100.0f);
            }
            case UNHAPPY -> {
                // Map happiness 0-20 to severity 100-0
                float happiness = needs.getHappiness();
                yield 100.0f - (happiness / NeedsComponent.HAPPINESS_CRITICAL * 100.0f);
            }
        };
    }
}
