package com.excelsies.hycolonies.ecs.system;

import com.excelsies.hycolonies.ecs.component.CitizenComponent;
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
 * ECS System that handles wandering behavior for colony citizens.
 * This system ticks all entities and filters for those with CitizenComponent.
 *
 * Phase 1 Implementation: Basic logging to verify the system is running.
 * Future phases will add actual navigation via Hytale's NavMesh/Pathfinder API.
 */
public class WanderSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    // Tick counter to avoid spamming logs
    private int tickCounter = 0;
    private static final int LOG_INTERVAL = 200; // Log every 200 ticks (~10 seconds at 20 TPS)

    public WanderSystem() {
        super();
    }

    /**
     * Define the query - tick all entities (filter manually for CitizenComponent).
     * This is necessary because CitizenComponent.getComponentType() may be null
     * during system registration.
     */
    @Override
    public Query<EntityStore> getQuery() {
        // For Phase 1, use any() and filter in tick()
        // This ensures the system registers properly before our component is registered
        return Query.any();
    }

    /**
     * Called each tick for each entity matching the query.
     *
     * @param deltaTime Time since last tick
     * @param index Entity index within the chunk
     * @param chunk The archetype chunk containing entities
     * @param store The entity store
     * @param commandBuffer Buffer for queuing component changes
     */
    @Override
    public void tick(float deltaTime, int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        // Skip if CitizenComponent not registered yet
        if (CitizenComponent.getComponentType() == null) {
            return;
        }

        // Get the entity reference
        Ref<EntityStore> ref = chunk.getReferenceTo(index);

        // Try to get CitizenComponent - if null, this entity is not a citizen
        CitizenComponent citizen = store.getComponent(ref, CitizenComponent.getComponentType());
        if (citizen == null) {
            return;
        }

        // Increment tick counter (shared across all citizen entities)
        tickCounter++;

        // Log periodically to verify system is running
//        if (tickCounter >= LOG_INTERVAL) {
//            tickCounter = 0;
//            LOGGER.atInfo().log("WanderSystem tick - Citizen: %s (Colony: %s)",
//                    citizen.getCitizenName(),
//                    citizen.getColonyId() != null ? citizen.getColonyId().toString().substring(0, 8) : "none");
//        }

        // Phase 1: Basic wandering behavior placeholder
        // Future implementation will:
        // 1. Get current position via TransformComponent
        // 2. Choose random destination within colony bounds
        // 3. Use Hytale's pathfinding API to navigate
        // 4. Handle obstacles and unreachable targets
    }
}
