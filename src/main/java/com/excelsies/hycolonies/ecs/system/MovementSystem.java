package com.excelsies.hycolonies.ecs.system;

import com.excelsies.hycolonies.ecs.component.PathingComponent;
import com.excelsies.hycolonies.ecs.component.PathingStatus;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * ECS System that handles directed NPC movement for colony tasks.
 *
 * This system processes entities with PathingComponent and moves them toward
 * their target positions using the Teleport component for reliable position updates.
 *
 * <h2>Movement Architecture</h2>
 * <ul>
 *   <li><b>Idle Wandering:</b> Handled by NPC role templates (BodyMotion wander instructions)</li>
 *   <li><b>Directed Movement:</b> Handled by this system via PathingComponent + Teleport</li>
 * </ul>
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li>Other systems (CourierJobSystem, etc.) set PathingComponent with a target position</li>
 *   <li>This system detects entities with COMPUTING/MOVING status</li>
 *   <li>Uses Teleport component for incremental position updates (avoids client-server desync)</li>
 *   <li>Marks status as ARRIVED when within threshold of target</li>
 * </ol>
 *
 * <h2>Note on NPC Pathfinding</h2>
 * NPCs spawned via NPCPlugin have built-in A* pathfinding through their role system.
 * For complex navigation with obstacle avoidance, consider using NPCMessage or
 * BodyMotionFind instructions. This system provides simple direct movement for
 * short distances and job-related tasks.
 */
public class MovementSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Default movement speed in blocks per second.
     */
    private static final double DEFAULT_SPEED = 4.0;

    /**
     * Distance threshold for considering arrival at target (squared).
     */
    private static final double ARRIVAL_THRESHOLD_SQUARED = 0.25; // 0.5 blocks

    /**
     * Maximum time without movement progress before considering stuck.
     */
    private static final long STUCK_TIMEOUT_MS = 5000; // 5 seconds

    /**
     * Maximum retry count for path computation before giving up.
     */
    private static final int MAX_RETRIES = 3;

    public MovementSystem() {
        super();
    }

    @Override
    public Query<EntityStore> getQuery() {
        // Query all entities, filter manually in tick()
        // This is safer since components may not be registered when this is called
        return Query.any();
    }

    @Override
    public void tick(float deltaTime, int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {

        Ref<EntityStore> ref = chunk.getReferenceTo(index);

        // Skip if PathingComponent not registered yet
        if (PathingComponent.getComponentType() == null) {
            return;
        }

        // Get pathing component - filter to only entities with pathing
        PathingComponent pathing = store.getComponent(ref, PathingComponent.getComponentType());
        if (pathing == null) {
            return;
        }

        // Skip if not actively pathing
        if (pathing.getStatus() == PathingStatus.IDLE ||
            pathing.getStatus() == PathingStatus.ARRIVED ||
            pathing.getStatus() == PathingStatus.BLOCKED) {
            return;
        }

        // Get transform for position
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return;
        }

        // Process based on current status
        switch (pathing.getStatus()) {
            case COMPUTING -> handleComputing(ref, pathing, transform, store, commandBuffer);
            case MOVING -> handleMoving(ref, pathing, transform, deltaTime, store, commandBuffer);
        }
    }

    /**
     * Handles COMPUTING status - waiting for path to be computed.
     */
    private void handleComputing(Ref<EntityStore> ref, PathingComponent pathing,
                                  TransformComponent transform, Store<EntityStore> store,
                                  CommandBuffer<EntityStore> commandBuffer) {

        // TODO: When Hytale pathfinding API is available, request path computation:
        //
        // PathRequest request = Pathfinder.requestPath(
        //     transform.getPosition(),
        //     pathing.getTarget(),
        //     entity
        // );
        // if (request.isComplete()) {
        //     if (request.isSuccess()) {
        //         pathing.setPath(request.getPath());
        //         pathing.setStatus(PathingStatus.MOVING);
        //     } else {
        //         pathing.incrementRetryCount();
        //         if (pathing.hasExceededRetries(MAX_RETRIES)) {
        //             pathing.setStatus(PathingStatus.FAILED);
        //         }
        //     }
        // }

        // For now, immediately transition to MOVING (direct line movement)
        pathing.setStatus(PathingStatus.MOVING);
        pathing.updateMoveTime();
    }

    /**
     * Handles MOVING status - actively moving toward target.
     * Uses Teleport component for reliable position updates (avoids client-server desync).
     */
    private void handleMoving(Ref<EntityStore> ref, PathingComponent pathing,
                              TransformComponent transform, float deltaTime,
                              Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {

        Vector3d currentPos = transform.getPosition();
        Vector3d targetPos = pathing.getTarget();

        // Check if arrived
        if (pathing.hasArrived(currentPos, Math.sqrt(ARRIVAL_THRESHOLD_SQUARED))) {
            pathing.setStatus(PathingStatus.ARRIVED);
            LOGGER.atFine().log("Entity arrived at target: %s", targetPos);
            return;
        }

        // Check for stuck condition
        if (pathing.isStuck(STUCK_TIMEOUT_MS)) {
            handleStuck(ref, pathing, commandBuffer);
            LOGGER.atFine().log("Entity stuck while moving to: %s", targetPos);
            return;
        }

        // Calculate movement
        double dx = targetPos.getX() - currentPos.getX();
        double dy = targetPos.getY() - currentPos.getY();
        double dz = targetPos.getZ() - currentPos.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distance < 0.01) {
            // Essentially at target
            pathing.setStatus(PathingStatus.ARRIVED);
            return;
        }

        // Normalize and scale by speed and delta time
        double moveDistance = DEFAULT_SPEED * deltaTime;
        if (moveDistance > distance) {
            moveDistance = distance;
        }

        double scale = moveDistance / distance;
        double newX = currentPos.getX() + dx * scale;
        double newY = currentPos.getY() + dy * scale;
        double newZ = currentPos.getZ() + dz * scale;

        // Calculate rotation to face movement direction
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        // Create new position and rotation
        Vector3d newPos = new Vector3d(newX, newY, newZ);
        Vector3f currentRot = transform.getRotation();
        Vector3f newRot = new Vector3f(currentRot.getX(), yaw, currentRot.getZ());

        // Use Teleport component for reliable position updates
        // This avoids client-server desync that occurs with direct TransformComponent modification
        if (Teleport.getComponentType() != null) {
            Teleport teleport = new Teleport(newPos, newRot);
            commandBuffer.addComponent(ref, Teleport.getComponentType(), teleport);
        } else {
            // Fallback to direct transform update if Teleport not available
            TransformComponent newTransform = new TransformComponent(newPos, newRot);
            commandBuffer.addComponent(ref, TransformComponent.getComponentType(), newTransform);
        }

        // Update move time to indicate progress
        pathing.updateMoveTime();
    }

    /**
     * Handles stuck condition - entity couldn't make progress.
     */
    private void handleStuck(Ref<EntityStore> ref, PathingComponent pathing,
                             CommandBuffer<EntityStore> commandBuffer) {

        pathing.incrementRetryCount();

        if (pathing.hasExceededRetries(MAX_RETRIES)) {
            // Give up and mark as blocked
            pathing.setStatus(PathingStatus.BLOCKED);
            LOGGER.atWarning().log("Entity movement blocked after %d retries", MAX_RETRIES);
        } else {
            // Try again
            pathing.setStatus(PathingStatus.COMPUTING);
            LOGGER.atFine().log("Retrying path computation (attempt %d)", pathing.getRetryCount());
        }
    }
}
