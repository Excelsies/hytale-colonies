package com.excelsies.hycolonies.ecs.system;

import com.excelsies.hycolonies.logistics.service.LogisticsService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS System that triggers the logistics cycle on a configurable tick interval.
 * Runs every ~20 ticks (1 second at 20 TPS) to snapshot, solve, and execute.
 */
public class LogisticsTickSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Tick interval for logistics processing.
     * 20 ticks = 1 second at 20 TPS.
     */
    private static final int LOGISTICS_TICK_INTERVAL = 20;

    private final LogisticsService logisticsService;
    private int tickCounter = 0;
    private boolean hasTriggeredThisTick = false;

    public LogisticsTickSystem(LogisticsService logisticsService) {
        super();
        this.logisticsService = logisticsService;
    }

    @Override
    public Query<EntityStore> getQuery() {
        // We query any entity just to get ticked
        // The system only needs to run once per tick interval, not per entity
        return Query.any();
    }

    @Override
    public void tick(float deltaTime, int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        // Only process once per tick, not per entity
        // Use index == 0 as a simple way to trigger once
        if (index != 0) {
            return;
        }

        // Reset the flag at the start of each tick cycle
        if (!hasTriggeredThisTick) {
            tickCounter++;
            hasTriggeredThisTick = true;

            // Check if it's time to trigger logistics
            if (tickCounter >= LOGISTICS_TICK_INTERVAL) {
                tickCounter = 0;

                // Trigger the logistics cycle
                if (logisticsService != null) {
                    logisticsService.triggerLogisticsCycle(store);
                }
            }
        }
    }

    /**
     * Called at the end of each world tick.
     * Resets the per-tick flag.
     */
    public void endTick() {
        hasTriggeredThisTick = false;
    }
}
