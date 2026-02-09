package com.excelsies.hycolonies.logistics.model;

import javax.annotation.Nullable;

/**
 * Represents an item entry with ID, quantity, and optional metadata.
 * Used for tracking items in warehouse inventories and NPC inventories.
 */
public record ItemEntry(
    String itemId,
    int quantity,
    @Nullable String metadata
) {
    /**
     * Creates an ItemEntry without metadata.
     */
    public ItemEntry(String itemId, int quantity) {
        this(itemId, quantity, null);
    }

    /**
     * Creates a copy with modified quantity.
     */
    public ItemEntry withQuantity(int newQuantity) {
        return new ItemEntry(itemId, newQuantity, metadata);
    }

    /**
     * Returns true if this entry has a positive quantity.
     */
    public boolean hasItems() {
        return quantity > 0;
    }
}
