package com.excelsies.hycolonies.ecs.tag;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Marker component that indicates an NPC is idle and available for task assignment.
 * Added to citizens with jobs when they are not currently executing a task.
 * The JobOrchestratorSystem queries for entities with this tag to assign new work.
 */
public class IdleTag implements Component<EntityStore> {

    private static ComponentType<EntityStore, IdleTag> COMPONENT_TYPE;

    public static final BuilderCodec<IdleTag> CODEC = BuilderCodec.builder(IdleTag.class, IdleTag::new).build();

    /**
     * Default constructor.
     */
    public IdleTag() {
        // Marker component - no data
    }

    // Component type management
    public static ComponentType<EntityStore, IdleTag> getComponentType() {
        return COMPONENT_TYPE;
    }

    public static void setComponentType(ComponentType<EntityStore, IdleTag> type) {
        COMPONENT_TYPE = type;
    }

    @Override
    public Component<EntityStore> clone() {
        return new IdleTag();
    }
}
