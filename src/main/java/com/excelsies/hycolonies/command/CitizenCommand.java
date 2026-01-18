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
        addSubCommand(new SpawnSubCommand(colonyService));
        addSubCommand(new RemoveSubCommand(colonyService));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw("Usage:"));
        ctx.sendMessage(Message.raw("  /citizen add [colony-id] [name] - Add and spawn a citizen"));
        ctx.sendMessage(Message.raw("  /citizen list [colony-id] - List citizens"));
        ctx.sendMessage(Message.raw("  /citizen spawn [colony-id] [citizen-id] - Spawn existing citizen"));
        ctx.sendMessage(Message.raw("  /citizen remove [colony-id] [citizen-id] - Remove a citizen"));
    }

    /**
     * Spawns a citizen NPC on the world thread.
     * Must be called from within world.execute().
     *
     * @param store    The entity store
     * @param colonyId The colony UUID
     * @param citizen  The citizen data
     * @param npcSkin  The specific NPC skin to spawn
     * @return true if spawn was successful
     */
    private static boolean spawnCitizenNPC(Store<EntityStore> store, UUID colonyId, CitizenData citizen, String npcSkin) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            LOGGER.atWarning().log("NPCPlugin not available");
            return false;
        }

        Vector3d position = new Vector3d(citizen.getLastX(), citizen.getLastY(), citizen.getLastZ());
        Vector3f rotation = Vector3f.ZERO;

        List<String> availableRoles = npcPlugin.getRoleTemplateNames(true);
        if (availableRoles == null || availableRoles.isEmpty()) {
            LOGGER.atWarning().log("No NPC roles available");
            return false;
        }

        if (availableRoles.contains(npcSkin)) {
            try {
                Pair<Ref<EntityStore>, INonPlayerCharacter> result =
                        npcPlugin.spawnNPC(store, npcSkin, null, position, rotation);

                if (result != null && result.first() != null) {
                    Ref<EntityStore> entityRef = result.first();

                    if (CitizenComponent.getComponentType() != null) {
                        CitizenComponent citizenComponent = new CitizenComponent(
                                colonyId,
                                citizen.getCitizenId(),
                                citizen.getName(),
                                citizen.getNpcSkin()
                        );
                        store.addComponent(entityRef, CitizenComponent.getComponentType(), citizenComponent);
                    }

                    LOGGER.atInfo().log("Spawned citizen '%s' as %s at (%.0f, %.0f, %.0f)",
                            citizen.getName(), npcSkin,
                            position.getX(), position.getY(), position.getZ());
                    return true;
                }
            } catch (Exception e) {
                LOGGER.atFine().log("Failed to spawn %s: %s", npcSkin, e.getMessage());
            }
        } else {
            LOGGER.atWarning().log("NPC skin '%s' not found in available roles", npcSkin);
        }

        LOGGER.atWarning().log("Failed to spawn NPC for citizen '%s'", citizen.getName());
        return false;
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

                    // Select random skin based on faction
                    String npcSkin = colony.getFaction().getRandomSkin();
                    if (npcSkin == null) {
                        npcSkin = "Kweebec_Razorleaf"; // Fallback
                    }

                    CitizenData citizen = colonyService.addCitizen(colonyId, citizenName, x, y, z, npcSkin, false);

                    if (citizen != null) {
                        boolean spawned = spawnCitizenNPC(store, colonyId, citizen, npcSkin);

                        if (spawned) {
                            ctx.sendMessage(Message.raw("Spawned citizen '" + citizenName + "' (" + npcSkin + ") in " + colony.getName() + "!"));
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

    // =====================
    // Subcommand: spawn
    // =====================
    private static class SpawnSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<UUID> colonyIdArg;
        private final RequiredArg<UUID> citizenIdArg;

        public SpawnSubCommand(ColonyService colonyService) {
            super("spawn", "Spawn an existing citizen as an NPC entity");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony-id", "UUID of the colony", ArgTypes.UUID);
            this.citizenIdArg = withRequiredArg("citizen-id", "UUID of the citizen", ArgTypes.UUID);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            UUID colonyId = ctx.get(colonyIdArg);
            UUID citizenId = ctx.get(citizenIdArg);

            var colonyOpt = colonyService.getColony(colonyId);
            if (colonyOpt.isEmpty()) {
                ctx.sendMessage(Message.raw("Colony not found."));
                return;
            }

            ColonyData colony = colonyOpt.get();
            CitizenData citizen = colony.getCitizen(citizenId);

            if (citizen == null) {
                ctx.sendMessage(Message.raw("Citizen not found in colony."));
                return;
            }

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

                    // Get player position for spawning
                    double x = citizen.getLastX();
                    double y = citizen.getLastY();
                    double z = citizen.getLastZ();

                    TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
                    if (transform != null && transform.getPosition() != null) {
                        x = transform.getPosition().getX();
                        y = transform.getPosition().getY();
                        z = transform.getPosition().getZ();
                    }

                    citizen.updateLastPosition(x, y, z);

                    boolean spawned = spawnCitizenNPC(store, colonyId, citizen, citizen.getNpcSkin());

                    if (spawned) {
                        ctx.sendMessage(Message.raw("Spawned citizen '" + citizen.getName() + "' at " +
                                (int) x + ", " + (int) y + ", " + (int) z));
                    } else {
                        ctx.sendMessage(Message.raw("Failed to spawn citizen '" + citizen.getName() + "'."));
                    }
                } catch (Exception e) {
                    ctx.sendMessage(Message.raw("Error: " + e.getMessage()));
                    LOGGER.atWarning().log("Error spawning citizen: %s", e.getMessage());
                }
            });
        }
    }

    // =====================
    // Subcommand: remove
    // =====================
    private static class RemoveSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<UUID> colonyIdArg;
        private final RequiredArg<UUID> citizenIdArg;

        public RemoveSubCommand(ColonyService colonyService) {
            super("remove", "Remove a citizen from a colony");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony-id", "UUID of the colony", ArgTypes.UUID);
            this.citizenIdArg = withRequiredArg("citizen-id", "UUID of the citizen", ArgTypes.UUID);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            UUID colonyId = ctx.get(colonyIdArg);
            UUID citizenId = ctx.get(citizenIdArg);

            var colonyOpt = colonyService.getColony(colonyId);
            if (colonyOpt.isEmpty()) {
                ctx.sendMessage(Message.raw("Colony not found."));
                return;
            }

            ColonyData colony = colonyOpt.get();
            CitizenData removed = colonyService.removeCitizen(colonyId, citizenId);

            if (removed != null) {
                ctx.sendMessage(Message.raw("Removed citizen '" + removed.getName() + "' from " + colony.getName() + "."));
                ctx.sendMessage(Message.raw("Colony population: " + colony.getPopulation()));
                ctx.sendMessage(Message.raw("Note: Spawned NPC entity remains in world until chunk unloads."));
            } else {
                ctx.sendMessage(Message.raw("Citizen not found in colony."));
            }
        }
    }
}
