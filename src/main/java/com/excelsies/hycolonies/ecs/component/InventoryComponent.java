package com.excelsies.hycolonies.ecs.component;

import com.excelsies.hycolonies.logistics.model.ItemEntry;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ECS Component that provides a virtual inventory for NPCs.
 * Used for couriers to carry items during transport tasks.
 */
public class InventoryComponent implements Component<EntityStore> {

    private static ComponentType<EntityStore, InventoryComponent> COMPONENT_TYPE;

    /**
     * Default capacity for NPC inventories.
     */
    public static final int DEFAULT_MAX_SLOTS = 9;

    public static final BuilderCodec<InventoryComponent> CODEC = BuilderCodec.builder(InventoryComponent.class, InventoryComponent::new)
            .append(new KeyedCodec<>("MaxSlots", Codec.STRING),
                    (c, v, i) -> c.maxSlots = v != null && !v.isEmpty() ? Integer.parseInt(v) : DEFAULT_MAX_SLOTS,
                    (c, i) -> String.valueOf(c.maxSlots))
            .add()
            .append(new KeyedCodec<>("Contents", Codec.STRING),
                    (c, v, i) -> c.deserializeContents(v),
                    (c, i) -> c.serializeContents())
            .add()
            .build();

    private int maxSlots;
    private Map<Short, ItemEntry> slots;

    /**
     * Default constructor for deserialization.
     */
    public InventoryComponent() {
        this.maxSlots = DEFAULT_MAX_SLOTS;
        this.slots = new HashMap<>();
    }

    /**
     * Constructor with custom capacity.
     */
    public InventoryComponent(int maxSlots) {
        this.maxSlots = maxSlots;
        this.slots = new HashMap<>();
    }

    // Serialization helpers
    private String serializeContents() {
        if (slots.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Short, ItemEntry> entry : slots.entrySet()) {
            if (sb.length() > 0) sb.append(";");
            sb.append(entry.getKey())
              .append(":")
              .append(entry.getValue().itemId())
              .append(":")
              .append(entry.getValue().quantity());
        }
        return sb.toString();
    }

    private void deserializeContents(String data) {
        this.slots = new HashMap<>();
        if (data == null || data.isEmpty()) return;

        String[] entries = data.split(";");
        for (String entry : entries) {
            String[] parts = entry.split(":");
            if (parts.length >= 3) {
                try {
                    short slot = Short.parseShort(parts[0]);
                    String itemId = parts[1];
                    int quantity = Integer.parseInt(parts[2]);
                    slots.put(slot, new ItemEntry(itemId, quantity));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // Getters
    public int getMaxSlots() {
        return maxSlots;
    }

    public Map<Short, ItemEntry> getSlots() {
        return new HashMap<>(slots);
    }

    /**
     * Gets the item in a specific slot.
     */
    public ItemEntry getSlot(short slot) {
        return slots.get(slot);
    }

    /**
     * Returns all items as a list.
     */
    public List<ItemEntry> getAllItems() {
        return new ArrayList<>(slots.values());
    }

    /**
     * Returns true if the inventory is empty.
     */
    public boolean isEmpty() {
        return slots.isEmpty();
    }

    /**
     * Returns true if the inventory is full (all slots occupied).
     */
    public boolean isFull() {
        return slots.size() >= maxSlots;
    }

    /**
     * Returns the number of occupied slots.
     */
    public int getOccupiedSlots() {
        return slots.size();
    }

    /**
     * Returns the total quantity of a specific item across all slots.
     */
    public int getItemCount(String itemId) {
        return slots.values().stream()
                .filter(e -> e.itemId().equals(itemId))
                .mapToInt(ItemEntry::quantity)
                .sum();
    }

    /**
     * Returns true if the inventory contains at least the specified quantity of an item.
     */
    public boolean hasItem(String itemId, int minQuantity) {
        return getItemCount(itemId) >= minQuantity;
    }

    // Setters/Mutators

    /**
     * Adds an item to the inventory, stacking with existing items if possible.
     * @return true if the item was added, false if inventory is full
     */
    public boolean addItem(String itemId, int quantity) {
        // Try to stack with existing item
        for (Map.Entry<Short, ItemEntry> entry : slots.entrySet()) {
            if (entry.getValue().itemId().equals(itemId)) {
                slots.put(entry.getKey(), entry.getValue().withQuantity(
                        entry.getValue().quantity() + quantity));
                return true;
            }
        }

        // Find empty slot
        for (short i = 0; i < maxSlots; i++) {
            if (!slots.containsKey(i)) {
                slots.put(i, new ItemEntry(itemId, quantity));
                return true;
            }
        }

        return false; // Inventory full
    }

    /**
     * Removes a quantity of an item from the inventory.
     * @return true if the item was removed, false if not enough items
     */
    public boolean removeItem(String itemId, int quantity) {
        int remaining = quantity;

        for (Map.Entry<Short, ItemEntry> entry : new HashMap<>(slots).entrySet()) {
            if (entry.getValue().itemId().equals(itemId)) {
                int available = entry.getValue().quantity();
                if (available <= remaining) {
                    slots.remove(entry.getKey());
                    remaining -= available;
                } else {
                    slots.put(entry.getKey(), entry.getValue().withQuantity(available - remaining));
                    remaining = 0;
                }
                if (remaining <= 0) break;
            }
        }

        return remaining <= 0;
    }

    /**
     * Clears all items from the inventory.
     */
    public void clear() {
        slots.clear();
    }

    /**
     * Sets a specific slot's contents.
     */
    public void setSlot(short slot, ItemEntry item) {
        if (slot >= 0 && slot < maxSlots) {
            if (item == null || item.quantity() <= 0) {
                slots.remove(slot);
            } else {
                slots.put(slot, item);
            }
        }
    }

    // Component type management
    public static ComponentType<EntityStore, InventoryComponent> getComponentType() {
        return COMPONENT_TYPE;
    }

    public static void setComponentType(ComponentType<EntityStore, InventoryComponent> type) {
        COMPONENT_TYPE = type;
    }

    @Override
    public Component<EntityStore> clone() {
        InventoryComponent clone = new InventoryComponent(maxSlots);
        clone.slots = new HashMap<>(this.slots);
        return clone;
    }
}
