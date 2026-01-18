package com.excelsies.hycolonies.colony.model;

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
}
