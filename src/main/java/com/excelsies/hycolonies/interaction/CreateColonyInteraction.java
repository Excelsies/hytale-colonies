package com.excelsies.hycolonies.interaction;

import com.excelsies.hycolonies.HyColoniesPlugin;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.ui.ColonyCreationPage;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

public class CreateColonyInteraction extends SimpleInteraction {

    public static final BuilderCodec<CreateColonyInteraction> CODEC = BuilderCodec.builder(CreateColonyInteraction.class, CreateColonyInteraction::new).build();

    @Override
    public void handle(@NonNullDecl Ref<EntityStore> ref, boolean firstRun, float time, @NonNullDecl InteractionType type, @NonNullDecl InteractionContext interactionContext) {
        super.handle(ref, firstRun, time, type, interactionContext);
        
        var store = ref.getStore();
        if (store == null) return;
        
        // ref is the entity performing the interaction (the player)
        var player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRefComponent = store.getComponent(ref, PlayerRef.getComponentType());
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        
        if (player == null || playerRefComponent == null || transform == null) return;

        ColonyService colonyService = HyColoniesPlugin.get().getColonyService();
        
        // Try to get target position from context, fall back to player position
        // TODO: Use interactionContext.getTargetBlock() if available
        Vector3i targetPos = transform.getPosition().toVector3i();
        
        ColonyCreationPage page = new ColonyCreationPage(
                playerRefComponent,
                colonyService,
                targetPos,
                player.getWorld()
        );

        if (player.getPageManager() != null) {
            player.getPageManager().openCustomPage(ref, store, page);
        }
    }
}