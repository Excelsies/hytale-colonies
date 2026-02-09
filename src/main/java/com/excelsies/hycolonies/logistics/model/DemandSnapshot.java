package com.excelsies.hycolonies.logistics.model;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of all pending item requests at a point in time.
 * Used by the async LogisticsSolver to match supply with demand.
 */
public record DemandSnapshot(
    List<ItemRequest> requests,
    long snapshotTime
) {
    /**
     * Creates a snapshot with the current timestamp.
     * Defensively copies the request list for immutability.
     */
    public DemandSnapshot(List<ItemRequest> requests) {
        this(List.copyOf(requests), System.currentTimeMillis());
    }

    /**
     * Creates an empty snapshot.
     */
    public static DemandSnapshot empty() {
        return new DemandSnapshot(List.of(), System.currentTimeMillis());
    }

    /**
     * Returns requests sorted by priority (highest first), then by age (oldest first).
     */
    public List<ItemRequest> getSortedRequests() {
        return requests.stream()
                .sorted(Comparator
                        .comparingInt((ItemRequest r) -> -r.priority().getValue())
                        .thenComparingLong(ItemRequest::createdTime))
                .toList();
    }

    /**
     * Returns requests filtered by colony.
     */
    public List<ItemRequest> getRequestsForColony(UUID colonyId) {
        return requests.stream()
                .filter(r -> r.colonyId().equals(colonyId))
                .toList();
    }

    /**
     * Returns requests filtered by item type.
     */
    public List<ItemRequest> getRequestsForItem(String itemId) {
        return requests.stream()
                .filter(r -> r.itemId().equals(itemId))
                .toList();
    }

    /**
     * Returns requests filtered by priority level or higher.
     */
    public List<ItemRequest> getRequestsWithMinPriority(RequestPriority minPriority) {
        return requests.stream()
                .filter(r -> r.priority().getValue() >= minPriority.getValue())
                .toList();
    }

    /**
     * Groups requests by item ID for batch processing.
     */
    public Map<String, List<ItemRequest>> groupByItem() {
        return requests.stream()
                .collect(Collectors.groupingBy(ItemRequest::itemId));
    }

    /**
     * Returns the total quantity demanded for a specific item.
     */
    public int getTotalDemand(String itemId) {
        return requests.stream()
                .filter(r -> r.itemId().equals(itemId))
                .mapToInt(ItemRequest::quantity)
                .sum();
    }

    /**
     * Returns the age of this snapshot in milliseconds.
     */
    public long getAgeMs() {
        return System.currentTimeMillis() - snapshotTime;
    }

    /**
     * Returns true if there are any requests.
     */
    public boolean hasRequests() {
        return !requests.isEmpty();
    }

    /**
     * Returns the count of requests.
     */
    public int getRequestCount() {
        return requests.size();
    }
}
