package com.excelsies.hycolonies.command;

import com.excelsies.hycolonies.colony.model.CitizenData;
import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.ecs.component.CitizenComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

/**
 * Command for managing citizens in a colony.
 *
 * Usage:
 * - /citizen add [colony-id] [name] - Adds a citizen and spawns NPC at player location
 * - /citizen list [colony-id] - Lists all citizens in a colony
 * - /citizen spawn [colony-id] [citizen-id] - Spawns an existing citizen as NPC
 * - /citizen remove [colony-id] [citizen-id] - Removes a citizen from a colony
 */
public class CitizenCommand extends CommandBase {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public CitizenCommand(ColonyService colonyService) {
        super("citizen", "Manage citizens in colonies");
        this.setPermissionGroup(GameMode.Adventure);

        addSubCommand(new AddSubCommand(colonyService));
        addSubCommand(new ListSubCommand(colonyService));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw("Usage:"));
        ctx.sendMessage(Message.raw("  /citizen add [colony-id] [name] - Add and spawn a citizen"));
        ctx.sendMessage(Message.raw("  /citizen list [colony-id] - List citizens"));
    }

    // =====================
    // Subcommand: add
    // =====================
    private static class AddSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<UUID> colonyIdArg;
        private final RequiredArg<String> nameArg;

        public AddSubCommand(ColonyService colonyService) {
            super("add", "Add a citizen to a colony and spawn as NPC");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony-id", "UUID of the colony", ArgTypes.UUID);
            this.nameArg = withRequiredArg("name", "Name of the citizen", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            UUID colonyId = ctx.get(colonyIdArg);
            String citizenName = ctx.get(nameArg);

            var colonyOpt = colonyService.getColony(colonyId);
            if (colonyOpt.isEmpty()) {
                ctx.sendMessage(Message.raw("Colony not found."));
                return;
            }

            ColonyData colony = colonyOpt.get();

            if (!ctx.isPlayer()) {
                ctx.sendMessage(Message.raw("This command must be run by a player."));
                return;
            }

            Player player = ctx.senderAs(Player.class);
            if (player == null || player.getWorld() == null) {
                ctx.sendMessage(Message.raw("Could not get player world."));
                return;
            }

            World world = player.getWorld();
            Ref<EntityStore> playerRef = player.getReference();

            if (playerRef == null) {
                ctx.sendMessage(Message.raw("Could not get player reference."));
                return;
            }

            // All store operations must run on the world thread
            world.execute(() -> {
                try {
                    Store<EntityStore> store = playerRef.getStore();
                    if (store == null) {
                        ctx.sendMessage(Message.raw("Could not get entity store."));
                        return;
                    }

                    // Get player position
                    double x = 0, y = 64, z = 0;
                    TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
                    if (transform != null && transform.getPosition() != null) {
                        x = transform.getPosition().getX();
                        y = transform.getPosition().getY();
                        z = transform.getPosition().getZ();
                    }

                    CitizenData citizen = colonyService.addCitizen(colonyId, citizenName, x, y, z, false);

                    if (citizen != null) {
                        boolean spawned = spawnCitizenNPC(store, colonyId, citizen);

                        if (spawned) {
                            ctx.sendMessage(Message.raw("Spawned citizen '" + citizenName + "' in " + colony.getName() + "!"));
                        } else {
                            ctx.sendMessage(Message.raw("Added citizen '" + citizenName + "' but NPC spawn failed."));
                        }
                        ctx.sendMessage(Message.raw("Colony population: " + colony.getPopulation()));
                        ctx.sendMessage(Message.raw("Location: " + (int) x + ", " + (int) y + ", " + (int) z));
                    } else {
                        ctx.sendMessage(Message.raw("Failed to add citizen."));
                    }
                } catch (Exception e) {
                    ctx.sendMessage(Message.raw("Error: " + e.getMessage()));
                    LOGGER.atWarning().log("Error adding citizen: %s", e.getMessage());
                }
            });
        }
    }

    // =====================
    // Subcommand: list
    // =====================
    private static class ListSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<UUID> colonyIdArg;

        public ListSubCommand(ColonyService colonyService) {
            super("list", "List citizens in a colony");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony-id", "UUID of the colony", ArgTypes.UUID);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            UUID colonyId = ctx.get(colonyIdArg);

            var colonyOpt = colonyService.getColony(colonyId);
            if (colonyOpt.isEmpty()) {
                ctx.sendMessage(Message.raw("Colony not found."));
                return;
            }

            ColonyData colony = colonyOpt.get();

            if (colony.getPopulation() == 0) {
                ctx.sendMessage(Message.raw("Colony '" + colony.getName() + "' has no citizens yet."));
                ctx.sendMessage(Message.raw("Use /citizen add [colony-id] [name] to add one."));
                return;
            }

            ctx.sendMessage(Message.raw("=== Citizens of " + colony.getName() + " ==="));
            ctx.sendMessage(Message.raw("Population: " + colony.getPopulation()));
            colony.getCitizens().forEach(citizen ->
                    ctx.sendMessage(Message.raw("  - " + citizen.getName() +
                            " (ID: " + citizen.getCitizenId().toString().substring(0, 8) + "...)"))
            );
        }
    }
}
