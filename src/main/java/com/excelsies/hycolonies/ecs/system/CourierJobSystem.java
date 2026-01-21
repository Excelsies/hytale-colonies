package com.excelsies.hycolonies.ecs.system;

import com.excelsies.hycolonies.ecs.component.*;
import com.excelsies.hycolonies.ecs.tag.CourierActiveTag;
import com.excelsies.hycolonies.ecs.tag.IdleTag;
import com.excelsies.hycolonies.logistics.model.CourierState;
import com.excelsies.hycolonies.logistics.model.TransportInstruction;
import com.excelsies.hycolonies.logistics.service.LogisticsService;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * ECS System that processes the Courier job state machine.
 *
 * State transitions:
 * IDLE → MOVING_TO_SOURCE → PICKING_UP → MOVING_TO_DEST → DEPOSITING → RETURNING → IDLE
 *
 * Each state has specific entry/exit conditions and timeout handling.
 */
public class CourierJobSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Distance threshold (squared) for considering arrival at a destination.
     */
    private static final double ARRIVAL_THRESHOLD_SQUARED = 4.0; // 2 blocks

    /**
     * Maximum time in milliseconds to stay in any single state before timeout.
     */
    private static final long STATE_TIMEOUT_MS = 60000; // 60 seconds

    /**
     * Pickup/deposit action duration in milliseconds.
     */
    private static final long ACTION_DURATION_MS = 2000; // 2 seconds

    private final LogisticsService logisticsService;

    public CourierJobSystem(LogisticsService logisticsService) {
        super();
        this.logisticsService = logisticsService;
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

        // Check if CourierActiveTag is present (filtering for active couriers)
        if (CourierActiveTag.getComponentType() == null) {
            return;
        }
        CourierActiveTag activeTag = store.getComponent(ref, CourierActiveTag.getComponentType());
        if (activeTag == null) {
            return; // Not an active courier
        }

        // Get required components
        JobComponent job = store.getComponent(ref, JobComponent.getComponentType());
        if (job == null || job.getJobType() != JobType.COURIER) {
            return;
        }

        // Get transform for position checks
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }

        // Get or create pathing component
        PathingComponent pathing = store.getComponent(ref, PathingComponent.getComponentType());
        if (pathing == null) {
            pathing = new PathingComponent();
            commandBuffer.addComponent(ref, PathingComponent.getComponentType(), pathing);
        }

        // Get or create inventory component
        InventoryComponent inventory = store.getComponent(ref, InventoryComponent.getComponentType());
        if (inventory == null) {
            inventory = new InventoryComponent();
            commandBuffer.addComponent(ref, InventoryComponent.getComponentType(), inventory);
        }

        // Check for state timeout
        if (job.getTimeInCurrentState() > STATE_TIMEOUT_MS) {
            handleStateTimeout(ref, job, commandBuffer);
            return;
        }

        // Process current state
        CourierState currentState = CourierState.fromString(job.getCurrentState());
        processState(ref, currentState, job, pathing, inventory, transform, store, commandBuffer);
    }

    /**
     * Processes the current courier state and handles transitions.
     */
    private void processState(Ref<EntityStore> ref, CourierState state,
                             JobComponent job, PathingComponent pathing,
                             InventoryComponent inventory, TransformComponent transform,
                             Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {

        switch (state) {
            case IDLE -> handleIdle(ref, job, commandBuffer);
            case MOVING_TO_SOURCE -> handleMovingToSource(ref, job, pathing, transform, commandBuffer);
            case PICKING_UP -> handlePickingUp(ref, job, inventory, commandBuffer);
            case MOVING_TO_DEST -> handleMovingToDestination(ref, job, pathing, transform, commandBuffer);
            case DEPOSITING -> handleDepositing(ref, job, inventory, commandBuffer);
            case RETURNING -> handleReturning(ref, job, pathing, transform, commandBuffer);
        }
    }

    /**
     * IDLE state: Courier is waiting for an assignment.
     * This state is handled by LogisticsService which assigns instructions.
     */
    private void handleIdle(Ref<EntityStore> ref, JobComponent job, CommandBuffer<EntityStore> commandBuffer) {
        // Check if we have an assigned task
        if (job.getAssignedTaskId() == null) {
            // No task - ensure IdleTag is present and CourierActiveTag is removed
            if (IdleTag.getComponentType() != null) {
                commandBuffer.addComponent(ref, IdleTag.getComponentType(), new IdleTag());
            }
            commandBuffer.removeComponent(ref, CourierActiveTag.getComponentType());
            return;
        }

        // We have a task - transition to MOVING_TO_SOURCE
        TransportInstruction instruction = logisticsService.getActiveInstruction(job.getAssignedTaskId());
        if (instruction == null) {
            // Task was cancelled or completed
            job.clearTask();
            return;
        }

        job.setCurrentState(CourierState.MOVING_TO_SOURCE.name());
        LOGGER.atFine().log("Courier starting task: moving to source at %s",
                instruction.source().position());
    }

    /**
     * MOVING_TO_SOURCE state: Courier is walking to the pickup location.
     */
    private void handleMovingToSource(Ref<EntityStore> ref, JobComponent job,
                                       PathingComponent pathing, TransformComponent transform,
                                       CommandBuffer<EntityStore> commandBuffer) {

        TransportInstruction instruction = logisticsService.getActiveInstruction(job.getAssignedTaskId());
        if (instruction == null) {
            transitionToIdle(ref, job, commandBuffer);
            return;
        }

        // Set path target if not already set
        Vector3d currentPos = transform.getPosition();
        Vector3d sourcePos = new Vector3d(
                instruction.source().position().getX() + 0.5,
                instruction.source().position().getY(),
                instruction.source().position().getZ() + 0.5
        );

        if (pathing.getStatus() == PathingStatus.IDLE) {
            pathing.setTarget(sourcePos);
        }

        // Check if arrived at source
        if (pathing.hasArrived(currentPos, Math.sqrt(ARRIVAL_THRESHOLD_SQUARED))) {
            job.setCurrentState(CourierState.PICKING_UP.name());
            pathing.clearPath();
            LOGGER.atFine().log("Courier arrived at source, picking up items");
        }
    }

    /**
     * PICKING_UP state: Courier is taking items from the source warehouse.
     */
    private void handlePickingUp(Ref<EntityStore> ref, JobComponent job,
                                  InventoryComponent inventory, CommandBuffer<EntityStore> commandBuffer) {

        TransportInstruction instruction = logisticsService.getActiveInstruction(job.getAssignedTaskId());
        if (instruction == null) {
            transitionToIdle(ref, job, commandBuffer);
            return;
        }

        // Simulate pickup action with duration
        if (job.getTimeInCurrentState() < ACTION_DURATION_MS) {
            return; // Still picking up
        }

        // TODO: Actually remove items from warehouse when block inventory API is available
        // For now, just add items to courier inventory
        inventory.addItem(instruction.itemId(), instruction.quantity());

        job.setCurrentState(CourierState.MOVING_TO_DEST.name());
        LOGGER.atFine().log("Courier picked up %s x%d, moving to destination",
                instruction.itemId(), instruction.quantity());
    }

    /**
     * MOVING_TO_DEST state: Courier is walking to the delivery location.
     */
    private void handleMovingToDestination(Ref<EntityStore> ref, JobComponent job,
                                            PathingComponent pathing, TransformComponent transform,
                                            CommandBuffer<EntityStore> commandBuffer) {

        TransportInstruction instruction = logisticsService.getActiveInstruction(job.getAssignedTaskId());
        if (instruction == null) {
            // Task cancelled while carrying items - need to return them
            // For now, just drop and go idle
            transitionToIdle(ref, job, commandBuffer);
            return;
        }

        // Set path target if not already set
        Vector3d currentPos = transform.getPosition();
        Vector3d destPos = new Vector3d(
                instruction.destination().position().getX() + 0.5,
                instruction.destination().position().getY(),
                instruction.destination().position().getZ() + 0.5
        );

        if (pathing.getStatus() == PathingStatus.IDLE) {
            pathing.setTarget(destPos);
        }

        // Check if arrived at destination
        if (pathing.hasArrived(currentPos, Math.sqrt(ARRIVAL_THRESHOLD_SQUARED))) {
            job.setCurrentState(CourierState.DEPOSITING.name());
            pathing.clearPath();
            LOGGER.atFine().log("Courier arrived at destination, depositing items");
        }
    }

    /**
     * DEPOSITING state: Courier is placing items in the destination.
     */
    private void handleDepositing(Ref<EntityStore> ref, JobComponent job,
                                   InventoryComponent inventory, CommandBuffer<EntityStore> commandBuffer) {

        TransportInstruction instruction = logisticsService.getActiveInstruction(job.getAssignedTaskId());

        // Simulate deposit action with duration
        if (job.getTimeInCurrentState() < ACTION_DURATION_MS) {
            return; // Still depositing
        }

        // TODO: Actually add items to destination when block inventory API is available
        // For now, just remove items from courier inventory
        if (instruction != null) {
            inventory.removeItem(instruction.itemId(), instruction.quantity());

            // Mark instruction as completed
            logisticsService.completeInstruction(job.getAssignedTaskId());
        }

        // Clear inventory (in case of partial completion)
        inventory.clear();

        job.setCurrentState(CourierState.RETURNING.name());
        LOGGER.atFine().log("Courier deposited items, returning to idle position");
    }

    /**
     * RETURNING state: Courier is walking back to their home/idle position.
     */
    private void handleReturning(Ref<EntityStore> ref, JobComponent job,
                                  PathingComponent pathing, TransformComponent transform,
                                  CommandBuffer<EntityStore> commandBuffer) {

        // For now, just transition immediately to idle
        // In a full implementation, the courier would walk back to a designated spot
        job.clearTask();
        transitionToIdle(ref, job, commandBuffer);
        LOGGER.atFine().log("Courier returned to idle state");
    }

    /**
     * Handles state timeout by failing the current task and returning to idle.
     */
    private void handleStateTimeout(Ref<EntityStore> ref, JobComponent job,
                                    CommandBuffer<EntityStore> commandBuffer) {
        UUID taskId = job.getAssignedTaskId();

        if (taskId != null) {
            logisticsService.failInstruction(taskId, "State timeout: " + job.getCurrentState());
        }

        LOGGER.atWarning().log("Courier state timeout in %s, returning to idle", job.getCurrentState());
        transitionToIdle(ref, job, commandBuffer);
    }

    /**
     * Transitions the courier to idle state.
     */
    private void transitionToIdle(Ref<EntityStore> ref, JobComponent job,
                                  CommandBuffer<EntityStore> commandBuffer) {
        job.clearTask();
        job.setCurrentState(CourierState.IDLE.name());

        // Add IdleTag, remove CourierActiveTag
        if (IdleTag.getComponentType() != null) {
            commandBuffer.addComponent(ref, IdleTag.getComponentType(), new IdleTag());
        }
        commandBuffer.removeComponent(ref, CourierActiveTag.getComponentType());
    }
}
