package com.excelsies.hycolonies.logistics.model;

import com.hypixel.hytale.math.vector.Vector3i;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Represents a request for items to be delivered to a specific location.
 * Created by citizens (hunger), builders (materials), or work orders.
 */
public record ItemRequest(
    UUID requestId,
    UUID colonyId,
    String itemId,
    int quantity,
    Vector3i destinationPos,
    RequestPriority priority,
    RequestType type,
    @Nullable UUID requesterId,  // Citizen who made the request (null for building/manual)
    long createdTime
) {
    /**
     * Creates a new request with auto-generated ID and timestamp.
     */
    public ItemRequest(UUID colonyId, String itemId, int quantity, Vector3i destinationPos,
                       RequestPriority priority, RequestType type, @Nullable UUID requesterId) {
        this(UUID.randomUUID(), colonyId, itemId, quantity, destinationPos,
             priority, type, requesterId, System.currentTimeMillis());
    }

    /**
     * Creates a request with reduced quantity (for partial fulfillment).
     */
    public ItemRequest withQuantity(int newQuantity) {
        return new ItemRequest(requestId, colonyId, itemId, newQuantity, destinationPos,
                               priority, type, requesterId, createdTime);
    }

    /**
     * Returns true if this request can be fulfilled with the given quantity.
     */
    public boolean canFulfillWith(int availableQuantity) {
        return availableQuantity > 0;
    }

    /**
     * Returns the age of this request in milliseconds.
     */
    public long getAgeMs() {
        return System.currentTimeMillis() - createdTime;
    }
}
