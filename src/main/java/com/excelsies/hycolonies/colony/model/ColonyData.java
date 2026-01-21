package com.excelsies.hycolonies.colony.model;

import com.excelsies.hycolonies.warehouse.WarehouseData;
import com.hypixel.hytale.math.vector.Vector3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Serializable data model for persisting colony state to JSON.
 * Contains all persistent data about a colony including its citizens.
 */
public class ColonyData {

    private UUID colonyId;
    private String name;
    private UUID ownerUuid;
    private long createdTimestamp;
    private double centerX;
    private double centerY;
    private double centerZ;
    private String worldId;
    private Faction faction;
    private List<CitizenData> citizens;
    private List<WarehouseData> warehouses;

    /**
     * Default constructor for JSON deserialization.
     */
    public ColonyData() {
        this.colonyId = UUID.randomUUID();
        this.name = "New Colony";
        this.ownerUuid = null;
        this.createdTimestamp = System.currentTimeMillis();
        this.centerX = 0;
        this.centerY = 0;
        this.centerZ = 0;
        this.worldId = "default";
        this.faction = Faction.KWEEBEC;
        this.citizens = new ArrayList<>();
        this.warehouses = new ArrayList<>();
    }

    /**
     * Full constructor for creating a colony.
     */
    public ColonyData(UUID colonyId, String name, UUID ownerUuid,
                      double centerX, double centerY, double centerZ, String worldId, Faction faction) {
        this.colonyId = colonyId;
        this.name = name;
        this.ownerUuid = ownerUuid;
        this.createdTimestamp = System.currentTimeMillis();
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.worldId = worldId;
        this.faction = faction != null ? faction : Faction.KWEEBEC;
        this.citizens = new ArrayList<>();
        this.warehouses = new ArrayList<>();
    }

    // Getters
    public UUID getColonyId() {
        return colonyId;
    }

    public String getName() {
        return name;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }

    public double getCenterZ() {
        return centerZ;
    }

    public String getWorldId() {
        return worldId;
    }

    public Faction getFaction() {
        return faction != null ? faction : Faction.KWEEBEC;
    }

    /**
     * Returns an unmodifiable view of the citizens list.
     */
    public List<CitizenData> getCitizens() {
        return Collections.unmodifiableList(citizens);
    }

    /**
     * Returns the current population count.
     */
    public int getPopulation() {
        return citizens.size();
    }

    // Setters
    public void setName(String name) {
        this.name = name;
    }

    public void setFaction(Faction faction) {
        this.faction = faction;
    }

    // Citizen management
    /**
     * Adds a citizen to this colony.
     */
    public void addCitizen(CitizenData citizen) {
        this.citizens.add(citizen);
    }

    /**
     * Removes a citizen from this colony by ID.
     */
    public void removeCitizen(UUID citizenId) {
        this.citizens.removeIf(c -> c.getCitizenId().equals(citizenId));
    }

    /**
     * Gets a citizen by their ID.
     */
    public CitizenData getCitizen(UUID citizenId) {
        return citizens.stream()
                .filter(c -> c.getCitizenId().equals(citizenId))
                .findFirst()
                .orElse(null);
    }

    // Warehouse management
    /**
     * Returns an unmodifiable view of the warehouses list.
     */
    public List<WarehouseData> getWarehouses() {
        if (warehouses == null) {
            warehouses = new ArrayList<>();
        }
        return Collections.unmodifiableList(warehouses);
    }

    /**
     * Returns the number of registered warehouses.
     */
    public int getWarehouseCount() {
        return warehouses != null ? warehouses.size() : 0;
    }

    /**
     * Adds a warehouse to this colony.
     */
    public void addWarehouse(WarehouseData warehouse) {
        if (warehouses == null) {
            warehouses = new ArrayList<>();
        }
        // Check for duplicates by position
        if (!hasWarehouseAt(warehouse.getPosition())) {
            this.warehouses.add(warehouse);
        }
    }

    /**
     * Removes a warehouse by its position.
     */
    public void removeWarehouse(Vector3i position) {
        if (warehouses != null) {
            warehouses.removeIf(w -> w.isAtPosition(position));
        }
    }

    /**
     * Gets a warehouse by its position.
     */
    public WarehouseData getWarehouse(Vector3i position) {
        if (warehouses == null) return null;
        return warehouses.stream()
                .filter(w -> w.isAtPosition(position))
                .findFirst()
                .orElse(null);
    }

    /**
     * Checks if a warehouse exists at the given position.
     */
    public boolean hasWarehouseAt(Vector3i position) {
        if (warehouses == null) return false;
        return warehouses.stream()
                .anyMatch(w -> w.isAtPosition(position));
    }
}
