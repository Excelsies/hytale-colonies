package com.excelsies.hycolonies.colony.model;

import java.util.UUID;

/**
 * Serializable data model for persisting citizen state to JSON.
 * This is separate from CitizenComponent to decouple persistence from ECS.
 */
public class CitizenData {

    private UUID citizenId;
    private String name;
    private String npcSkin;
    private String skinCosmetics; // JSON string of cosmetic configuration for Avatar citizens
    private double lastX;
    private double lastY;
    private double lastZ;
    private long createdTimestamp;

    /**
     * Default constructor for JSON deserialization.
     */
    public CitizenData() {
        this.citizenId = UUID.randomUUID();
        this.name = "Citizen";
        this.npcSkin = "Kweebec_Razorleaf";
        this.skinCosmetics = null;
        this.lastX = 0;
        this.lastY = 0;
        this.lastZ = 0;
        this.createdTimestamp = System.currentTimeMillis();
    }

    /**
     * Full constructor for creating citizen data.
     */
    public CitizenData(UUID citizenId, String name, String npcSkin, double x, double y, double z) {
        this(citizenId, name, npcSkin, null, x, y, z);
    }

    /**
     * Full constructor with cosmetics.
     */
    public CitizenData(UUID citizenId, String name, String npcSkin, String skinCosmetics, double x, double y, double z) {
        this.citizenId = citizenId;
        this.name = name;
        this.npcSkin = npcSkin;
        this.skinCosmetics = skinCosmetics;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.createdTimestamp = System.currentTimeMillis();
    }

    // Getters
    public UUID getCitizenId() {
        return citizenId;
    }

    public String getName() {
        return name;
    }

    public String getNpcSkin() {
        return npcSkin;
    }

    public String getSkinCosmetics() {
        return skinCosmetics;
    }

    public double getLastX() {
        return lastX;
    }

    public double getLastY() {
        return lastY;
    }

    public double getLastZ() {
        return lastZ;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    // Setters
    public void setName(String name) {
        this.name = name;
    }

    public void setNpcSkin(String npcSkin) {
        this.npcSkin = npcSkin;
    }

    public void setSkinCosmetics(String skinCosmetics) {
        this.skinCosmetics = skinCosmetics;
    }

    /**
     * Updates the last known position of the citizen.
     */
    public void updateLastPosition(double x, double y, double z) {
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
    }
}