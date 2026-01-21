package com.excelsies.hycolonies.logistics.service;

import com.excelsies.hycolonies.logistics.model.*;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.*;

/**
 * Pure algorithm class that matches supply to demand using a greedy matching strategy.
 * This runs on a worker thread with immutable/thread-safe data only.
 *
 * The algorithm:
 * 1. Sort requests by priority (highest first), then by age (oldest first)
 * 2. For each request, find the nearest warehouse with the item
 * 3. For each source, find the nearest available courier
 * 4. Generate TransportInstruction and track allocations
 */
public class LogisticsSolver {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Solves the supply-demand matching problem.
     * This method is thread-safe as it only uses immutable input data.
     *
     * @param supply     Immutable snapshot of all warehouse contents
     * @param demand     Immutable snapshot of all pending requests
     * @param couriers   List of available couriers with their positions
     * @return List of transport instructions to execute
     */
    public List<TransportInstruction> solve(SupplySnapshot supply,
                                             DemandSnapshot demand,
                                             List<CourierInfo> couriers) {
        List<TransportInstruction> instructions = new ArrayList<>();

        if (!demand.hasRequests() || couriers.isEmpty()) {
            return instructions;
        }

        // Get sorted requests (by priority desc, then age asc)
        List<ItemRequest> sortedRequests = demand.getSortedRequests();

        // Create mutable allocation map to track which items have been assigned
        Map<WarehouseLocation, Map<String, Integer>> availableItems = supply.toMutableAllocationMap();

        // Track which couriers have been assigned tasks
        Set<UUID> assignedCouriers = new HashSet<>();

        for (ItemRequest request : sortedRequests) {
            // Skip if we've run out of couriers
            if (assignedCouriers.size() >= couriers.size()) {
                LOGGER.atFine().log("No more couriers available, stopping solve");
                break;
            }

            // Find all warehouses with the requested item (within same colony)
            List<WarehouseLocation> sourcesWithItem = findSourcesWithItem(
                    request.colonyId(),
                    request.itemId(),
                    availableItems
            );

            if (sourcesWithItem.isEmpty()) {
                LOGGER.atFine().log("No source found for item %s in colony %s",
                        request.itemId(), request.colonyId());
                continue;
            }

            // Find the nearest source to the destination
            WarehouseLocation source = findNearestSource(
                    request.destinationPos(),
                    sourcesWithItem
            );

            if (source == null) {
                continue;
            }

            // Find nearest available courier (prefer couriers in same colony)
            CourierInfo courier = findBestCourier(
                    source,
                    request.colonyId(),
                    couriers,
                    assignedCouriers
            );

            if (courier == null) {
                LOGGER.atFine().log("No courier available for request %s", request.requestId());
                continue;
            }

            // Calculate how much we can fulfill
            int availableQuantity = getAvailableQuantity(availableItems, source, request.itemId());
            int fulfillQuantity = Math.min(request.quantity(), availableQuantity);

            if (fulfillQuantity <= 0) {
                continue;
            }

            // Also limit by courier capacity
            fulfillQuantity = Math.min(fulfillQuantity, courier.carryCapacity());

            // Create the transport instruction
            WarehouseLocation destination = new WarehouseLocation(
                    request.colonyId(),
                    request.destinationPos()
            );

            TransportInstruction instruction = new TransportInstruction(
                    courier.citizenId(),
                    source,
                    destination,
                    request.itemId(),
                    fulfillQuantity,
                    request.priority(),
                    request.requestId()
            );

            instructions.add(instruction);

            // Update tracking
            deductFromAvailable(availableItems, source, request.itemId(), fulfillQuantity);
            assignedCouriers.add(courier.citizenId());

            LOGGER.atFine().log("Generated instruction: %s x%d from %s to %s via courier %s",
                    request.itemId(), fulfillQuantity,
                    source.position(), destination.position(),
                    courier.citizenId().toString().substring(0, 8));
        }

        LOGGER.atInfo().log("Logistics solve complete: %d instructions from %d requests with %d couriers",
                instructions.size(), sortedRequests.size(), couriers.size());

        return instructions;
    }

    /**
     * Finds all warehouse locations in a colony that have the specified item available.
     */
    private List<WarehouseLocation> findSourcesWithItem(UUID colonyId, String itemId,
                                                         Map<WarehouseLocation, Map<String, Integer>> available) {
        List<WarehouseLocation> result = new ArrayList<>();

        for (var entry : available.entrySet()) {
            WarehouseLocation loc = entry.getKey();
            if (!loc.colonyId().equals(colonyId)) {
                continue;
            }

            Integer quantity = entry.getValue().get(itemId);
            if (quantity != null && quantity > 0) {
                result.add(loc);
            }
        }

        return result;
    }

    /**
     * Finds the nearest warehouse to a destination position.
     */
    private WarehouseLocation findNearestSource(com.hypixel.hytale.math.vector.Vector3i destination,
                                                 List<WarehouseLocation> sources) {
        WarehouseLocation nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (WarehouseLocation source : sources) {
            double distSq = source.distanceSquaredTo(destination);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = source;
            }
        }

        return nearest;
    }

    /**
     * Finds the best available courier for a task.
     * Prefers couriers in the same colony, then by distance to source.
     */
    private CourierInfo findBestCourier(WarehouseLocation source, UUID preferredColony,
                                         List<CourierInfo> couriers, Set<UUID> assignedCouriers) {
        CourierInfo best = null;
        double bestDistSq = Double.MAX_VALUE;
        boolean bestIsSameColony = false;

        for (CourierInfo courier : couriers) {
            if (assignedCouriers.contains(courier.citizenId())) {
                continue;
            }

            boolean sameColony = courier.colonyId().equals(preferredColony);
            double distSq = courier.distanceSquaredTo(source);

            // Prefer same colony couriers
            if (sameColony && !bestIsSameColony) {
                best = courier;
                bestDistSq = distSq;
                bestIsSameColony = true;
            } else if (sameColony == bestIsSameColony && distSq < bestDistSq) {
                best = courier;
                bestDistSq = distSq;
            }
        }

        return best;
    }

    /**
     * Gets the available quantity of an item at a warehouse location.
     */
    private int getAvailableQuantity(Map<WarehouseLocation, Map<String, Integer>> available,
                                      WarehouseLocation source, String itemId) {
        Map<String, Integer> items = available.get(source);
        if (items == null) return 0;

        Integer quantity = items.get(itemId);
        return quantity != null ? quantity : 0;
    }

    /**
     * Deducts a quantity from the available items tracking map.
     */
    private void deductFromAvailable(Map<WarehouseLocation, Map<String, Integer>> available,
                                      WarehouseLocation source, String itemId, int quantity) {
        Map<String, Integer> items = available.get(source);
        if (items == null) return;

        Integer current = items.get(itemId);
        if (current != null) {
            int newQuantity = current - quantity;
            if (newQuantity > 0) {
                items.put(itemId, newQuantity);
            } else {
                items.remove(itemId);
            }
        }
    }
}
