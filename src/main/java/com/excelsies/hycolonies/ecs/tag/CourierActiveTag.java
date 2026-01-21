package com.excelsies.hycolonies.ecs.tag;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Marker component that indicates an NPC is actively executing a courier task.
 * Used by CourierJobSystem to filter which entities need state machine processing.
 * Removed when the courier returns to idle state.
 */
public class CourierActiveTag implements Component<EntityStore> {

    private static ComponentType<EntityStore, CourierActiveTag> COMPONENT_TYPE;

    public static final BuilderCodec<CourierActiveTag> CODEC = BuilderCodec.builder(CourierActiveTag.class, CourierActiveTag::new).build();

    /**
     * Default constructor.
     */
    public CourierActiveTag() {
        // Marker component - no data
    }

    // Component type management
    public static ComponentType<EntityStore, CourierActiveTag> getComponentType() {
        return COMPONENT_TYPE;
    }

    public static void setComponentType(ComponentType<EntityStore, CourierActiveTag> type) {
        COMPONENT_TYPE = type;
    }

    @Override
    public Component<EntityStore> clone() {
        return new CourierActiveTag();
    }
}
