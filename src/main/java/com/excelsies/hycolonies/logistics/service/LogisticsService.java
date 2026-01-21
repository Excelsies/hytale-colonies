package com.excelsies.hycolonies.logistics.service;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.ecs.component.CitizenComponent;
import com.excelsies.hycolonies.ecs.component.JobComponent;
import com.excelsies.hycolonies.ecs.component.JobType;
import com.excelsies.hycolonies.ecs.tag.CourierActiveTag;
import com.excelsies.hycolonies.ecs.tag.IdleTag;
import com.excelsies.hycolonies.logistics.model.*;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central orchestrator for the three-phase logistics cycle.
 *
 * Phase 1 (SYNC - Main Thread): Create immutable snapshots from cache
 * Phase 2 (ASYNC - Worker Thread): Run solver algorithm
 * Phase 3 (SYNC - Main Thread): Validate and execute instructions
 */
public class LogisticsService {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final InventoryCacheService inventoryCache;
    private final ColonyService colonyService;
    private final LogisticsSolver solver;
    private final ExecutorService asyncExecutor;

    // Pending requests waiting to be processed
    private final ConcurrentLinkedQueue<ItemRequest> pendingRequests;

    // Pending instructions from async solver waiting for main thread execution
    private final ConcurrentLinkedQueue<TransportInstruction> pendingInstructions;

    // Active instructions being executed by couriers (instructionId -> instruction)
    private final ConcurrentHashMap<UUID, TransportInstruction> activeInstructions;

    // Lock to prevent concurrent solve operations
    private final AtomicBoolean solveInProgress;

    // Statistics
    private long totalRequestsProcessed;
    private long totalInstructionsGenerated;
    private long lastSolveTimeMs;

    public LogisticsService(InventoryCacheService inventoryCache, ColonyService colonyService) {
        this.inventoryCache = inventoryCache;
        this.colonyService = colonyService;
        this.solver = new LogisticsSolver();
        this.asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HyColonies-Logistics");
            t.setDaemon(true);
            return t;
        });
        this.pendingRequests = new ConcurrentLinkedQueue<>();
        this.pendingInstructions = new ConcurrentLinkedQueue<>();
        this.activeInstructions = new ConcurrentHashMap<>();
        this.solveInProgress = new AtomicBoolean(false);
        this.totalRequestsProcessed = 0;
        this.totalInstructionsGenerated = 0;
        this.lastSolveTimeMs = 0;
    }

    /**
     * Triggers the three-phase logistics cycle.
     * Called by LogisticsTickSystem on the main thread.
     *
     * @param store The entity store for querying courier positions
     * @param commandBuffer The command buffer for deferred component modifications
     */
    public void triggerLogisticsCycle(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        // Phase 3: Execute pending instructions from previous cycle
        executePhaseThree(store, commandBuffer);

        // Don't start new solve if one is in progress
        if (!solveInProgress.compareAndSet(false, true)) {
            return;
        }

        // Phase 1: Create immutable snapshots (SYNC - fast O(N) copy)
        SupplySnapshot supply = createSupplySnapshot();
        DemandSnapshot demand = createDemandSnapshot();
        List<CourierInfo> availableCouriers = collectAvailableCouriers(store);

        // Phase 2: Solve asynchronously (ASYNC - on worker thread)
        asyncExecutor.submit(() -> {
            try {
                long startTime = System.currentTimeMillis();

                List<TransportInstruction> instructions = solver.solve(supply, demand, availableCouriers);

                // Queue instructions for Phase 3 execution
                pendingInstructions.addAll(instructions);

                lastSolveTimeMs = System.currentTimeMillis() - startTime;
                totalInstructionsGenerated += instructions.size();

                if (!instructions.isEmpty()) {
                    LOGGER.atInfo().log("Logistics solve completed in %dms: %d instructions generated",
                            lastSolveTimeMs, instructions.size());
                }
            } catch (Exception e) {
                LOGGER.atWarning().withCause(e).log("Logistics solve failed");
            } finally {
                solveInProgress.set(false);
            }
        });
    }

    /**
     * Phase 1: Creates an immutable supply snapshot from the cache.
     */
    private SupplySnapshot createSupplySnapshot() {
        return inventoryCache.createSnapshot();
    }

    /**
     * Phase 1: Creates an immutable demand snapshot from pending requests.
     */
    private DemandSnapshot createDemandSnapshot() {
        List<ItemRequest> requests = new ArrayList<>();
        ItemRequest request;
        while ((request = pendingRequests.poll()) != null) {
            requests.add(request);
            totalRequestsProcessed++;
        }
        return new DemandSnapshot(requests);
    }

    /**
     * Phase 1: Collects all available (idle) couriers and their positions.
     */
    private List<CourierInfo> collectAvailableCouriers(Store<EntityStore> store) {
        List<CourierInfo> couriers = new ArrayList<>();

        // This is called on the main thread, safe to query ECS
        if (JobComponent.getComponentType() == null ||
            CitizenComponent.getComponentType() == null ||
            IdleTag.getComponentType() == null) {
            return couriers;
        }

        // Iterate through all colonies and their citizens
        for (ColonyData colony : colonyService.getAllColonies()) {
            UUID colonyId = colony.getColonyId();

            for (var citizen : colony.getCitizens()) {
                // Get the entity reference from tracking
                Ref<EntityStore> entityRef = colonyService.getCitizenEntity(citizen.getCitizenId());
                if (entityRef == null) {
                    continue; // Citizen not spawned
                }

                // Check if they have a JobComponent with COURIER job type
                JobComponent job = store.getComponent(entityRef, JobComponent.getComponentType());
                if (job == null || job.getJobType() != JobType.COURIER) {
                    continue; // Not a courier
                }

                // Check if they are idle (have IdleTag)
                IdleTag idleTag = store.getComponent(entityRef, IdleTag.getComponentType());
                if (idleTag == null) {
                    continue; // Courier is busy
                }

                // Get their position
                TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
                if (transform == null || transform.getPosition() == null) {
                    continue;
                }

                Vector3d position = transform.getPosition();

                // Create CourierInfo for the solver
                CourierInfo courierInfo = new CourierInfo(
                        citizen.getCitizenId(),
                        colonyId,
                        position,
                        CourierInfo.DEFAULT_CAPACITY
                );
                couriers.add(courierInfo);

                LOGGER.atFine().log("Found available courier: %s at (%.0f, %.0f, %.0f)",
                        citizen.getName(), position.getX(), position.getY(), position.getZ());
            }
        }

        LOGGER.atFine().log("Collected %d available couriers", couriers.size());
        return couriers;
    }

    /**
     * Phase 3: Validates and executes pending instructions.
     */
    private void executePhaseThree(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        TransportInstruction instruction;
        while ((instruction = pendingInstructions.poll()) != null) {
            validateAndExecute(instruction, store, commandBuffer);
        }
    }

    /**
     * Validates an instruction against current world state and assigns to courier.
     */
    private void validateAndExecute(TransportInstruction instruction, Store<EntityStore> store,
                                     CommandBuffer<EntityStore> commandBuffer) {
        // 1. Verify source still has the items
        List<ItemEntry> sourceContents = inventoryCache.getWarehouseContents(
                instruction.source().colonyId(),
                instruction.source().position()
        );

        int available = sourceContents.stream()
                .filter(e -> e.itemId().equals(instruction.itemId()))
                .mapToInt(ItemEntry::quantity)
                .sum();

        if (available < instruction.quantity()) {
            LOGGER.atInfo().log("Instruction invalidated: insufficient items at source (%d < %d)",
                    available, instruction.quantity());
            return;
        }

        // 2. Get the courier entity and verify they're still available
        UUID courierId = instruction.courierId();
        if (courierId == null) {
            LOGGER.atWarning().log("Instruction has no assigned courier, skipping");
            return;
        }

        Ref<EntityStore> courierRef = colonyService.getCitizenEntity(courierId);
        if (courierRef == null) {
            LOGGER.atInfo().log("Courier %s not spawned, instruction invalidated", courierId);
            return;
        }

        // 3. Verify courier is still idle
        if (IdleTag.getComponentType() == null || JobComponent.getComponentType() == null) {
            LOGGER.atWarning().log("ECS component types not registered, skipping courier assignment");
            return;
        }

        IdleTag idleTag = store.getComponent(courierRef, IdleTag.getComponentType());
        if (idleTag == null) {
            LOGGER.atInfo().log("Courier %s no longer idle, instruction invalidated", courierId);
            return;
        }

        JobComponent job = store.getComponent(courierRef, JobComponent.getComponentType());
        if (job == null || job.getJobType() != JobType.COURIER) {
            LOGGER.atInfo().log("Entity %s is not a courier, instruction invalidated", courierId);
            return;
        }

        // 4. Assign the instruction to the courier
        // Create updated JobComponent with the task assignment
        JobComponent updatedJob = new JobComponent(
                job.getJobType(),
                CourierState.IDLE.name(),  // Will transition to MOVING_TO_SOURCE in CourierJobSystem
                instruction.instructionId(),
                job.getExperiencePoints()
        );
        commandBuffer.addComponent(courierRef, JobComponent.getComponentType(), updatedJob);

        // 5. Update tags: remove IdleTag, add CourierActiveTag
        commandBuffer.removeComponent(courierRef, IdleTag.getComponentType());
        if (CourierActiveTag.getComponentType() != null) {
            commandBuffer.addComponent(courierRef, CourierActiveTag.getComponentType(), new CourierActiveTag());
        }

        // 6. Track the instruction as active
        activeInstructions.put(instruction.instructionId(), instruction);

        LOGGER.atInfo().log("Courier %s assigned to transport %s x%d from %s to %s",
                courierId, instruction.itemId(), instruction.quantity(),
                instruction.source().position(), instruction.destination().position());
    }

    // === Public API for creating requests ===

    /**
     * Submits a new item request to the logistics system.
     *
     * @param request The item request
     */
    public void submitRequest(ItemRequest request) {
        pendingRequests.add(request);
        LOGGER.atFine().log("Request submitted: %s x%d for colony %s",
                request.itemId(), request.quantity(), request.colonyId());
    }

    /**
     * Creates and submits a request for items.
     */
    public ItemRequest createRequest(UUID colonyId, String itemId, int quantity,
                                       com.hypixel.hytale.math.vector.Vector3i destination,
                                       RequestPriority priority, RequestType type) {
        ItemRequest request = new ItemRequest(colonyId, itemId, quantity, destination, priority, type, null);
        submitRequest(request);
        return request;
    }

    /**
     * Gets an active instruction by ID.
     */
    public TransportInstruction getActiveInstruction(UUID instructionId) {
        return activeInstructions.get(instructionId);
    }

    /**
     * Marks an instruction as completed and removes it.
     */
    public void completeInstruction(UUID instructionId) {
        TransportInstruction removed = activeInstructions.remove(instructionId);
        if (removed != null) {
            LOGGER.atInfo().log("Instruction completed: %s", instructionId);
        }
    }

    /**
     * Marks an instruction as failed and removes it.
     */
    public void failInstruction(UUID instructionId, String reason) {
        TransportInstruction removed = activeInstructions.remove(instructionId);
        if (removed != null) {
            LOGGER.atWarning().log("Instruction failed (%s): %s", reason, instructionId);
            // Could re-queue the original request here
        }
    }

    // === Status and Statistics ===

    /**
     * Returns true if a solve operation is currently in progress.
     */
    public boolean isSolveInProgress() {
        return solveInProgress.get();
    }

    /**
     * Gets the number of pending requests.
     */
    public int getPendingRequestCount() {
        return pendingRequests.size();
    }

    /**
     * Gets the number of pending instructions.
     */
    public int getPendingInstructionCount() {
        return pendingInstructions.size();
    }

    /**
     * Gets the number of active (in-progress) instructions.
     */
    public int getActiveInstructionCount() {
        return activeInstructions.size();
    }

    /**
     * Gets all active instructions.
     */
    public Collection<TransportInstruction> getActiveInstructions() {
        return Collections.unmodifiableCollection(activeInstructions.values());
    }

    /**
     * Gets the total requests processed since startup.
     */
    public long getTotalRequestsProcessed() {
        return totalRequestsProcessed;
    }

    /**
     * Gets the total instructions generated since startup.
     */
    public long getTotalInstructionsGenerated() {
        return totalInstructionsGenerated;
    }

    /**
     * Gets the last solve time in milliseconds.
     */
    public long getLastSolveTimeMs() {
        return lastSolveTimeMs;
    }

    /**
     * Shuts down the logistics service.
     */
    public void shutdown() {
        LOGGER.atInfo().log("Shutting down LogisticsService...");
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.atInfo().log("LogisticsService shutdown complete");
    }
}
