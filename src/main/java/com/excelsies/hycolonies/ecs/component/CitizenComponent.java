package com.excelsies.hycolonies.ecs.component;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/**
 * ECS Component that marks an entity as a colony citizen and stores citizen-specific data.
 * This component is attached to NPC entities that belong to a colony.
 */
public class CitizenComponent implements Component<EntityStore> {

    // Static component type - set during registration
    private static ComponentType<EntityStore, CitizenComponent> COMPONENT_TYPE;

    // Codec for JSON serialization
    public static final BuilderCodec<CitizenComponent> CODEC = BuilderCodec.builder(CitizenComponent.class, CitizenComponent::new)
            .append(new KeyedCodec<>("ColonyId", Codec.STRING),
                    (c, v, i) -> c.colonyId = v != null ? UUID.fromString(v) : null,
                    (c, i) -> c.colonyId != null ? c.colonyId.toString() : null)
            .add()
            .append(new KeyedCodec<>("CitizenId", Codec.STRING),
                    (c, v, i) -> c.citizenId = v != null ? UUID.fromString(v) : null,
                    (c, i) -> c.citizenId != null ? c.citizenId.toString() : null)
            .add()
            .append(new KeyedCodec<>("CitizenName", Codec.STRING),
                    (c, v, i) -> c.citizenName = v,
                    (c, i) -> c.citizenName)
            .add()
            .append(new KeyedCodec<>("SkinId", Codec.STRING),
                    (c, v, i) -> c.skinId = v,
                    (c, i) -> c.skinId)
            .add()
            .build();

    private UUID colonyId;
    private UUID citizenId;
    private String citizenName;
    private String skinId;

    /**
     * Default constructor for deserialization.
     */
    public CitizenComponent() {
        this.colonyId = null;
        this.citizenId = UUID.randomUUID();
        this.citizenName = "Citizen";
        this.skinId = "default_citizen";
    }

    /**
     * Full constructor for creating a new citizen component.
     */
    public CitizenComponent(UUID colonyId, UUID citizenId, String citizenName, String skinId) {
        this.colonyId = colonyId;
        this.citizenId = citizenId;
        this.citizenName = citizenName;
        this.skinId = skinId;
    }

    // Getters
    public UUID getColonyId() {
        return colonyId;
    }

    public UUID getCitizenId() {
        return citizenId;
    }

    public String getCitizenName() {
        return citizenName;
    }

    public String getSkinId() {
        return skinId;
    }

    // Setters
    public void setColonyId(UUID colonyId) {
        this.colonyId = colonyId;
    }

    public void setCitizenName(String citizenName) {
        this.citizenName = citizenName;
    }

    public void setSkinId(String skinId) {
        this.skinId = skinId;
    }

    /**
     * Get the component type. This is set during registration.
     */
    public static ComponentType<EntityStore, CitizenComponent> getComponentType() {
        return COMPONENT_TYPE;
    }

    /**
     * Set the component type during registration.
     */
    public static void setComponentType(ComponentType<EntityStore, CitizenComponent> type) {
        COMPONENT_TYPE = type;
    }

    @Override
    public Component<EntityStore> clone() {
        return new CitizenComponent(colonyId, citizenId, citizenName, skinId);
    }
}
