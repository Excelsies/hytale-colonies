package com.excelsies.hycolonies.warehouse;

import com.hypixel.hytale.math.vector.Vector3i;

/**
 * Serializable data model for warehouse blocks in a colony.
 * Stored in ColonyData for persistence.
 */
public class WarehouseData {

    private int posX;
    private int posY;
    private int posZ;
    private String blockType;
    private int capacity;
    private long registeredTime;
    private String worldId;

    /**
     * Default constructor for JSON deserialization.
     */
    public WarehouseData() {
        this.posX = 0;
        this.posY = 0;
        this.posZ = 0;
        this.blockType = "Chest";
        this.capacity = 27;
        this.registeredTime = System.currentTimeMillis();
        this.worldId = "default";
    }

    /**
     * Full constructor.
     */
    public WarehouseData(Vector3i position, String blockType, int capacity, String worldId) {
        this.posX = position.getX();
        this.posY = position.getY();
        this.posZ = position.getZ();
        this.blockType = blockType;
        this.capacity = capacity;
        this.registeredTime = System.currentTimeMillis();
        this.worldId = worldId;
    }

    /**
     * Simple constructor with just position.
     */
    public WarehouseData(Vector3i position) {
        this(position, "Chest", 27, "default");
    }

    // Getters
    public Vector3i getPosition() {
        return new Vector3i(posX, posY, posZ);
    }

    public int getPosX() {
        return posX;
    }

    public int getPosY() {
        return posY;
    }

    public int getPosZ() {
        return posZ;
    }

    public String getBlockType() {
        return blockType;
    }

    public int getCapacity() {
        return capacity;
    }

    public long getRegisteredTime() {
        return registeredTime;
    }

    public String getWorldId() {
        return worldId;
    }

    // Setters
    public void setPosition(Vector3i position) {
        this.posX = position.getX();
        this.posY = position.getY();
        this.posZ = position.getZ();
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public void setWorldId(String worldId) {
        this.worldId = worldId;
    }

    /**
     * Returns true if this warehouse is at the given position.
     */
    public boolean isAtPosition(Vector3i position) {
        return posX == position.getX() &&
               posY == position.getY() &&
               posZ == position.getZ();
    }

    /**
     * Returns true if this warehouse is in the given world.
     */
    public boolean isInWorld(String world) {
        return worldId.equals(world);
    }

    @Override
    public String toString() {
        return String.format("Warehouse[%s at (%d, %d, %d) in %s]",
                blockType, posX, posY, posZ, worldId);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        WarehouseData other = (WarehouseData) obj;
        return posX == other.posX &&
               posY == other.posY &&
               posZ == other.posZ &&
               worldId.equals(other.worldId);
    }

    @Override
    public int hashCode() {
        int result = posX;
        result = 31 * result + posY;
        result = 31 * result + posZ;
        result = 31 * result + worldId.hashCode();
        return result;
    }
}
