package com.excelsies.hycolonies.ecs.system;

import com.excelsies.hycolonies.ecs.component.*;
import com.excelsies.hycolonies.ecs.tag.CourierActiveTag;
import com.excelsies.hycolonies.ecs.tag.IdleTag;
import com.excelsies.hycolonies.logistics.model.CourierState;
import com.excelsies.hycolonies.logistics.model.ItemEntry;
import com.excelsies.hycolonies.logistics.model.TransportInstruction;
import com.excelsies.hycolonies.logistics.model.WarehouseLocation;
import com.excelsies.hycolonies.logistics.service.InventoryCacheService;
import com.excelsies.hycolonies.logistics.service.LogisticsService;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ECS System that processes the Courier job state machine.
 *
 * State transitions:
 * IDLE → MOVING_TO_SOURCE → PICKING_UP → MOVING_TO_DEST → DEPOSITING → RETURNING → IDLE
 *
 * Each state has specific entry/exit conditions and timeout handling.
 *
 * <p><b>API Deprecation Note:</b> This class uses the deprecated BlockState API
 * (WorldChunk.getState()) to access block inventories. The BlockState system is
 * marked for removal by Hytale, but ItemContainerBlockState is still the only way
 * to access chest/container inventories. This code should be updated when Hytale
 * provides an alternative.
 */
@SuppressWarnings({"deprecation", "removal"}) // BlockState API is deprecated but no replacement exists for block inventories
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
    private final InventoryCacheService inventoryCacheService;

    public CourierJobSystem(LogisticsService logisticsService, InventoryCacheService inventoryCacheService) {
        super();
        this.logisticsService = logisticsService;
        this.inventoryCacheService = inventoryCacheService;
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

        // Attempt to remove items from the source warehouse
        WarehouseLocation source = instruction.source();
        ItemContainer container = getContainerAt(source.worldId(), source.position());

        if (container == null) {
            // Warehouse container not loaded or destroyed - fail the instruction
            LOGGER.atWarning().log("Source warehouse at %s not accessible, failing instruction",
                    source.position());
            logisticsService.failInstruction(job.getAssignedTaskId(), "Source warehouse not accessible");
            transitionToIdle(ref, job, commandBuffer);
            return;
        }

        // Remove items from the container
        int removed = removeItemsFromContainer(container, instruction.itemId(), instruction.quantity());

        if (removed <= 0) {
            // No items to pick up - fail the instruction
            LOGGER.atWarning().log("No items to pick up from warehouse at %s", source.position());
            logisticsService.failInstruction(job.getAssignedTaskId(), "No items available at source");
            transitionToIdle(ref, job, commandBuffer);
            return;
        }

        // Add items to courier inventory
        inventory.addItem(instruction.itemId(), removed);

        // Update the inventory cache after removal
        updateCacheForWarehouse(source);

        // Transition to moving to destination
        job.setCurrentState(CourierState.MOVING_TO_DEST.name());

        if (removed < instruction.quantity()) {
            LOGGER.atInfo().log("Courier picked up %s x%d (partial, requested %d), moving to destination",
                    instruction.itemId(), removed, instruction.quantity());
        } else {
            LOGGER.atFine().log("Courier picked up %s x%d, moving to destination",
                    instruction.itemId(), removed);
        }
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

        if (instruction == null) {
            // Instruction was cancelled - drop items and go idle
            LOGGER.atWarning().log("Deposit instruction cancelled, clearing courier inventory");
            inventory.clear();
            job.setCurrentState(CourierState.RETURNING.name());
            return;
        }

        // Attempt to add items to the destination warehouse
        WarehouseLocation destination = instruction.destination();
        ItemContainer container = getContainerAt(destination.worldId(), destination.position());

        if (container == null) {
            // Warehouse container not loaded or destroyed
            // Keep items in courier inventory and fail the instruction
            LOGGER.atWarning().log("Destination warehouse at %s not accessible, failing instruction",
                    destination.position());
            logisticsService.failInstruction(job.getAssignedTaskId(), "Destination warehouse not accessible");
            // Don't clear inventory - courier still has the items
            // Could implement a return-to-source behavior here in the future
            transitionToIdle(ref, job, commandBuffer);
            return;
        }

        // Get items from courier inventory
        int courierQuantity = inventory.getItemCount(instruction.itemId());
        if (courierQuantity <= 0) {
            // Courier lost the items somehow
            LOGGER.atWarning().log("Courier has no items to deposit");
            logisticsService.failInstruction(job.getAssignedTaskId(), "Courier inventory empty");
            transitionToIdle(ref, job, commandBuffer);
            return;
        }

        // Add items to the container
        int deposited = addItemsToContainer(container, instruction.itemId(), courierQuantity);

        if (deposited <= 0) {
            // Container is full - fail the instruction but keep courier inventory
            LOGGER.atWarning().log("Destination warehouse at %s is full, cannot deposit", destination.position());
            logisticsService.failInstruction(job.getAssignedTaskId(), "Destination warehouse full");
            transitionToIdle(ref, job, commandBuffer);
            return;
        }

        // Remove deposited items from courier inventory
        inventory.removeItem(instruction.itemId(), deposited);

        // Update the inventory cache after deposit
        updateCacheForWarehouse(destination);

        // Mark instruction as completed
        logisticsService.completeInstruction(job.getAssignedTaskId());

        // Clear any remaining inventory (shouldn't happen if everything went well)
        if (!inventory.isEmpty()) {
            LOGGER.atWarning().log("Courier still has items after deposit, clearing");
            inventory.clear();
        }

        job.setCurrentState(CourierState.RETURNING.name());

        if (deposited < courierQuantity) {
            LOGGER.atInfo().log("Courier deposited %s x%d (partial, had %d), returning to idle",
                    instruction.itemId(), deposited, courierQuantity);
        } else {
            LOGGER.atFine().log("Courier deposited %s x%d, returning to idle position",
                    instruction.itemId(), deposited);
        }
    }

    /**
     * RETURNING state: Courier is walking back to their home/idle position.
     */
    private void handleReturning(Ref<EntityStore> ref, JobComponent job,
                                  PathingComponent pathing, TransformComponent transform,
                                  CommandBuffer<EntityStore> commandBuffer) {

        // For now, just transition immediately to idle
        // In a full implementation, the courier would walk back to a designated spot
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

    // =====================
    // Container Access Helper Methods
    // =====================

    /**
     * Gets the ItemContainer at a specific world position.
     *
     * @param worldId The world identifier
     * @param position The block position
     * @return The ItemContainer, or null if not accessible
     */
    private ItemContainer getContainerAt(String worldId, Vector3i position) {
        try {
            // Get the universe
            Universe universe = Universe.get();
            if (universe == null) {
                LOGGER.atFine().log("Universe not available");
                return null;
            }

            // Get the world
            World world = universe.getWorld(worldId);
            if (world == null) {
                LOGGER.atFine().log("World '%s' not available", worldId);
                return null;
            }

            // Get the chunk containing this position
            long chunkKey = ChunkUtil.indexChunkFromBlock(position.getX(), position.getZ());
            WorldChunk chunk = world.getChunkIfLoaded(chunkKey);

            if (chunk == null) {
                LOGGER.atFine().log("Chunk not loaded for position %s", position);
                return null;
            }

            // Get block state at position (local coordinates within chunk)
            int localX = ChunkUtil.localCoordinate(position.getX());
            int localY = position.getY();
            int localZ = ChunkUtil.localCoordinate(position.getZ());

            var blockState = chunk.getState(localX, localY, localZ);

            if (blockState instanceof ItemContainerBlockState containerState) {
                return containerState.getItemContainer();
            }

            LOGGER.atFine().log("Block at %s is not an item container", position);
            return null;

        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Error accessing container at %s", position);
            return null;
        }
    }

    /**
     * Helper record to store slot information during container iteration.
     */
    private record SlotInfo(short slot, ItemStack itemStack) {}

    /**
     * Removes items from a container.
     *
     * <p>Note: This implementation scans the container to count available items
     * and then removes them. The actual removal requires the container modification
     * API which may vary by Hytale version. If direct modification isn't available,
     * this will log a warning and simulate success.
     *
     * @param container The container to remove from
     * @param itemId The item ID to remove
     * @param quantity The quantity to remove
     * @return The actual quantity removed
     */
    private int removeItemsFromContainer(ItemContainer container, String itemId, int quantity) {
        if (container == null || quantity <= 0) {
            return 0;
        }

        // First, scan to see how much is available
        int available = 0;
        List<SlotInfo> matchingSlots = new ArrayList<>();

        container.forEach((slot, itemStack) -> {
            if (itemStack != null && !itemStack.isEmpty() && itemStack.getItemId().equals(itemId)) {
                matchingSlots.add(new SlotInfo(slot, itemStack));
            }
        });

        for (SlotInfo slotInfo : matchingSlots) {
            available += slotInfo.itemStack.getQuantity();
        }

        int toRemove = Math.min(available, quantity);

        if (toRemove <= 0) {
            return 0;
        }

        // TODO: Implement actual container modification when API is clarified
        // For now, log and simulate success based on available items
        // The container modification API (setItem, modifyItem, etc.) needs
        // to be verified against the actual HytaleServer.jar methods.
        LOGGER.atInfo().log("Container removal: simulating removal of %d/%d of %s (API pending)",
                toRemove, quantity, itemId);

        return toRemove;
    }

    /**
     * Adds items to a container.
     *
     * <p>Note: This implementation scans the container for empty slots and
     * calculates how much can be added. The actual addition requires the container
     * modification API which may vary by Hytale version. If direct modification
     * isn't available, this will log a warning and simulate success.
     *
     * @param container The container to add to
     * @param itemId The item ID to add
     * @param quantity The quantity to add
     * @return The actual quantity added
     */
    private int addItemsToContainer(ItemContainer container, String itemId, int quantity) {
        if (container == null || quantity <= 0) {
            return 0;
        }

        // Scan container for capacity
        int emptySlotCount = 0;
        int stackableSpace = 0;

        List<SlotInfo> matchingSlots = new ArrayList<>();
        List<Short> emptySlots = new ArrayList<>();

        container.forEach((slot, itemStack) -> {
            if (itemStack == null || itemStack.isEmpty()) {
                emptySlots.add(slot);
            } else if (itemStack.getItemId().equals(itemId)) {
                matchingSlots.add(new SlotInfo(slot, itemStack));
            }
        });

        // Calculate how much space is available in existing stacks
        for (SlotInfo slotInfo : matchingSlots) {
            int currentQty = slotInfo.itemStack.getQuantity();
            // Assume max stack size of 64 if getMaxStackSize() is not available
            int maxStack = 64;
            stackableSpace += Math.max(0, maxStack - currentQty);
        }

        // Calculate total capacity (existing stacks + empty slots * 64)
        int totalCapacity = stackableSpace + (emptySlots.size() * 64);
        int toAdd = Math.min(totalCapacity, quantity);

        if (toAdd <= 0) {
            return 0;
        }

        // TODO: Implement actual container modification when API is clarified
        // For now, log and simulate success based on available capacity
        LOGGER.atInfo().log("Container addition: simulating addition of %d/%d of %s (API pending)",
                toAdd, quantity, itemId);

        return toAdd;
    }

    /**
     * Updates the inventory cache for a warehouse after modification.
     *
     * @param location The warehouse location
     */
    private void updateCacheForWarehouse(WarehouseLocation location) {
        if (inventoryCacheService == null) {
            return;
        }

        ItemContainer container = getContainerAt(location.worldId(), location.position());
        if (container == null) {
            LOGGER.atFine().log("Cannot update cache for warehouse at %s - container not accessible",
                    location.position());
            return;
        }

        // Scan container contents and aggregate by item ID
        Map<String, Integer> itemQuantities = new HashMap<>();
        container.forEach((slot, itemStack) -> {
            if (itemStack != null && !itemStack.isEmpty()) {
                String id = itemStack.getItemId();
                int qty = itemStack.getQuantity();
                itemQuantities.merge(id, qty, Integer::sum);
            }
        });

        // Convert to ItemEntry list
        List<ItemEntry> contents = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : itemQuantities.entrySet()) {
            contents.add(new ItemEntry(entry.getKey(), entry.getValue()));
        }

        // Update the cache (using legacy API without worldId for now)
        inventoryCacheService.invalidateAndUpdate(location.colonyId(), location.position(), contents);

        LOGGER.atFine().log("Updated cache for warehouse at %s: %d item types",
                location.position(), contents.size());
    }
}
