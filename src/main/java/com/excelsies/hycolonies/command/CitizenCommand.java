package com.excelsies.hycolonies.command;

import com.excelsies.hycolonies.colony.model.CitizenData;
import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.ecs.component.CitizenComponent;
import com.excelsies.hycolonies.ecs.component.JobComponent;
import com.excelsies.hycolonies.ecs.component.JobType;
import com.excelsies.hycolonies.ecs.tag.IdleTag;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
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
        addSubCommand(new JobSubCommand(colonyService));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw("Usage:"));
        ctx.sendMessage(Message.raw("  /citizen add [colony] [name] [skin] - Add and spawn a citizen"));
        ctx.sendMessage(Message.raw("  /citizen list [colony] - List citizens"));
        ctx.sendMessage(Message.raw("  /citizen spawn [colony] [citizen] [skin] - Spawn existing citizen"));
        ctx.sendMessage(Message.raw("  /citizen remove [colony] [citizen] - Remove a citizen"));
        ctx.sendMessage(Message.raw("  /citizen job [colony] [citizen] [job] - Assign a job to a citizen"));
        ctx.sendMessage(Message.raw("Note: Colony and citizen can be specified by name or UUID."));
        ctx.sendMessage(Message.raw("      Skin is optional; uses random faction skin if not specified."));
        ctx.sendMessage(Message.raw("      Jobs: unemployed, courier, builder, farmer, miner, guard"));
    }

    /**
     * Helper to resolve a colony and send error messages if needed.
     * Returns the colony if found uniquely, null otherwise (with messages sent).
     */
    private static ColonyData resolveColonyWithFeedback(ColonyService colonyService, CommandContext ctx, String identifier) {
        var result = colonyService.resolveColony(identifier);
        if (result.isNotFound()) {
            ctx.sendMessage(Message.raw("Colony not found: " + identifier));
            return null;
        }
        if (result.hasMultipleMatches()) {
            ctx.sendMessage(Message.raw("Multiple colonies found with name '" + identifier + "'. Please specify UUID:"));
            for (ColonyData match : result.getMultipleMatches()) {
                ctx.sendMessage(Message.raw("  - " + match.getName() + " [" + match.getFaction().getDisplayName() + "]"));
                ctx.sendMessage(Message.raw("    ID: " + match.getColonyId()));
            }
            return null;
        }
        return result.getColony();
    }

    /**
     * Helper to resolve a citizen and send error messages if needed.
     * Returns the citizen if found uniquely, null otherwise (with messages sent).
     */
    private static CitizenData resolveCitizenWithFeedback(ColonyService colonyService, CommandContext ctx,
                                                           ColonyData colony, String identifier) {
        var result = colonyService.resolveCitizen(colony.getColonyId(), identifier);
        if (result.isNotFound()) {
            ctx.sendMessage(Message.raw("Citizen not found in " + colony.getName() + ": " + identifier));
            return null;
        }
        if (result.hasMultipleMatches()) {
            ctx.sendMessage(Message.raw("Multiple citizens found with name '" + identifier + "'. Please specify UUID:"));
            for (CitizenData match : result.getMultipleMatches()) {
                ctx.sendMessage(Message.raw("  - " + match.getName() + " (" + match.getNpcSkin() + ")"));
                ctx.sendMessage(Message.raw("    ID: " + match.getCitizenId()));
            }
            return null;
        }
        return result.getCitizen();
    }

    /**
     * Spawns a citizen NPC on the world thread.
     * Must be called from within world.execute().
     *
     * @param store         The entity store
     * @param world         The world to spawn in (for entity tracking)
     * @param colonyService The colony service for entity registration
     * @param colonyId      The colony UUID
     * @param citizen       The citizen data
     * @param npcSkin       The specific NPC skin to spawn
     * @return true if spawn was successful
     */
    private static boolean spawnCitizenNPC(Store<EntityStore> store, World world, ColonyService colonyService,
                                            UUID colonyId, CitizenData citizen, String npcSkin) {
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

                    // Register the entity reference with ColonyService for later lookup and cleanup
                    colonyService.registerCitizenEntity(citizen.getCitizenId(), entityRef, world);

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
        private final RequiredArg<String> colonyIdArg;
        private final RequiredArg<String> nameArg;
        private final OptionalArg<String> skinArg;

        public AddSubCommand(ColonyService colonyService) {
            super("add", "Add a citizen to a colony and spawn as NPC");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony", "Colony name or UUID", ArgTypes.STRING);
            this.nameArg = withRequiredArg("name", "Name of the citizen", ArgTypes.STRING);
            this.skinArg = withOptionalArg("skin", "Specific skin variant (optional)", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);
            String citizenName = ctx.get(nameArg);
            String requestedSkin = ctx.get(skinArg);

            ColonyData colony = resolveColonyWithFeedback(colonyService, ctx, colonyIdentifier);
            if (colony == null) {
                return;
            }

            UUID colonyId = colony.getColonyId();

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

            // Resolve skin: use specified skin or random from faction
            String npcSkin;
            if (requestedSkin != null && !requestedSkin.isEmpty()) {
                // Try to find matching skin in faction
                String foundSkin = colony.getFaction().findSkin(requestedSkin);
                if (foundSkin != null) {
                    npcSkin = foundSkin;
                } else {
                    ctx.sendMessage(Message.raw("Skin '" + requestedSkin + "' not found in " + colony.getFaction().getDisplayName() + " faction."));
                    ctx.sendMessage(Message.raw("Available skins: " + String.join(", ", colony.getFaction().getNpcSkins())));
                    return;
                }
            } else {
                npcSkin = colony.getFaction().getRandomSkin();
                if (npcSkin == null) {
                    npcSkin = "Kweebec_Razorleaf"; // Fallback
                }
            }

            final String finalNpcSkin = npcSkin;

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

                    CitizenData citizen = colonyService.addCitizen(colonyId, citizenName, x, y, z, finalNpcSkin, false);

                    if (citizen != null) {
                        boolean spawned = spawnCitizenNPC(store, world, colonyService, colonyId, citizen, finalNpcSkin);

                        if (spawned) {
                            ctx.sendMessage(Message.raw("Spawned citizen '" + citizenName + "' (" + finalNpcSkin + ") in " + colony.getName() + "!"));
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
        private final RequiredArg<String> colonyIdArg;

        public ListSubCommand(ColonyService colonyService) {
            super("list", "List citizens in a colony");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony", "Colony name or UUID", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);

            ColonyData colony = resolveColonyWithFeedback(colonyService, ctx, colonyIdentifier);
            if (colony == null) {
                return;
            }

            if (colony.getPopulation() == 0) {
                ctx.sendMessage(Message.raw("Colony '" + colony.getName() + "' has no citizens yet."));
                ctx.sendMessage(Message.raw("Use /citizen add [colony-id] [name] to add one."));
                return;
            }

            ctx.sendMessage(Message.raw("=== Citizens of " + colony.getName() + " ==="));
            ctx.sendMessage(Message.raw("Population: " + colony.getPopulation()));
            colony.getCitizens().forEach(citizen -> {
                ctx.sendMessage(Message.raw("  - " + citizen.getName() + " (" + citizen.getNpcSkin() + ")"));
                ctx.sendMessage(Message.raw("    ID: " + citizen.getCitizenId()));
            });
        }
    }

    // =====================
    // Subcommand: spawn
    // =====================
    private static class SpawnSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<String> colonyIdArg;
        private final RequiredArg<String> citizenIdArg;
        private final OptionalArg<String> skinArg;

        public SpawnSubCommand(ColonyService colonyService) {
            super("spawn", "Spawn an existing citizen as an NPC entity");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony", "Colony name or UUID", ArgTypes.STRING);
            this.citizenIdArg = withRequiredArg("citizen", "Citizen name or UUID", ArgTypes.STRING);
            this.skinArg = withOptionalArg("skin", "Override skin variant (optional)", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);
            String citizenIdentifier = ctx.get(citizenIdArg);
            String requestedSkin = ctx.get(skinArg);

            ColonyData colony = resolveColonyWithFeedback(colonyService, ctx, colonyIdentifier);
            if (colony == null) {
                return;
            }

            UUID colonyId = colony.getColonyId();
            CitizenData citizen = resolveCitizenWithFeedback(colonyService, ctx, colony, citizenIdentifier);
            if (citizen == null) {
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

            // Resolve skin: use specified skin, or fall back to citizen's stored skin
            String npcSkin;
            if (requestedSkin != null && !requestedSkin.isEmpty()) {
                // Try to find matching skin in faction
                String foundSkin = colony.getFaction().findSkin(requestedSkin);
                if (foundSkin != null) {
                    npcSkin = foundSkin;
                    // Update the citizen's stored skin
                    citizen.setNpcSkin(foundSkin);
                } else {
                    ctx.sendMessage(Message.raw("Skin '" + requestedSkin + "' not found in " + colony.getFaction().getDisplayName() + " faction."));
                    ctx.sendMessage(Message.raw("Available skins: " + String.join(", ", colony.getFaction().getNpcSkins())));
                    return;
                }
            } else {
                npcSkin = citizen.getNpcSkin();
            }

            final String finalNpcSkin = npcSkin;

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

                    boolean spawned = spawnCitizenNPC(store, world, colonyService, colonyId, citizen, finalNpcSkin);

                    if (spawned) {
                        ctx.sendMessage(Message.raw("Spawned citizen '" + citizen.getName() + "' (" + finalNpcSkin + ") at " +
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
        private final RequiredArg<String> colonyIdArg;
        private final RequiredArg<String> citizenIdArg;

        public RemoveSubCommand(ColonyService colonyService) {
            super("remove", "Remove a citizen from a colony");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony", "Colony name or UUID", ArgTypes.STRING);
            this.citizenIdArg = withRequiredArg("citizen", "Citizen name or UUID", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);
            String citizenIdentifier = ctx.get(citizenIdArg);

            ColonyData colony = resolveColonyWithFeedback(colonyService, ctx, colonyIdentifier);
            if (colony == null) {
                return;
            }

            CitizenData citizen = resolveCitizenWithFeedback(colonyService, ctx, colony, citizenIdentifier);
            if (citizen == null) {
                return;
            }

            // Remove citizen data and queue entity removal
            CitizenData removed = colonyService.removeCitizenWithEntity(colony.getColonyId(), citizen.getCitizenId());

            if (removed != null) {
                ctx.sendMessage(Message.raw("Removed citizen '" + removed.getName() + "' from " + colony.getName() + "."));
                ctx.sendMessage(Message.raw("Colony population: " + colony.getPopulation()));

                // Process pending entity removals immediately
                colonyService.processPendingEntityRemovals();
                ctx.sendMessage(Message.raw("NPC entity despawned."));
            } else {
                ctx.sendMessage(Message.raw("Citizen not found in colony."));
            }
        }
    }

    // =====================
    // Subcommand: job
    // =====================
    private static class JobSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<String> colonyIdArg;
        private final RequiredArg<String> citizenIdArg;
        private final RequiredArg<String> jobTypeArg;

        public JobSubCommand(ColonyService colonyService) {
            super("job", "Assign a job to a citizen");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony", "Colony name or UUID", ArgTypes.STRING);
            this.citizenIdArg = withRequiredArg("citizen", "Citizen name or UUID", ArgTypes.STRING);
            this.jobTypeArg = withRequiredArg("job", "Job type (courier, builder, farmer, miner, guard, unemployed)", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);
            String citizenIdentifier = ctx.get(citizenIdArg);
            String jobTypeStr = ctx.get(jobTypeArg);

            ColonyData colony = resolveColonyWithFeedback(colonyService, ctx, colonyIdentifier);
            if (colony == null) {
                return;
            }

            CitizenData citizen = resolveCitizenWithFeedback(colonyService, ctx, colony, citizenIdentifier);
            if (citizen == null) {
                return;
            }

            // Parse job type
            JobType jobType;
            try {
                jobType = JobType.valueOf(jobTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                ctx.sendMessage(Message.raw("Unknown job type: " + jobTypeStr));
                ctx.sendMessage(Message.raw("Available jobs: " + String.join(", ",
                        java.util.Arrays.stream(JobType.values())
                                .map(j -> j.name().toLowerCase())
                                .toArray(String[]::new))));
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
            UUID colonyId = colony.getColonyId();
            UUID citizenId = citizen.getCitizenId();

            // Find and update the citizen's entity on the world thread
            world.execute(() -> {
                Store<EntityStore> store = world.getEntityStore().getStore();

                // Find the entity with matching CitizenComponent
                boolean found = findAndUpdateCitizenJob(store, colonyId, citizenId, jobType);

                if (found) {
                    ctx.sendMessage(Message.raw("Assigned " + citizen.getName() + " as " + jobType.getDisplayName() + "."));
                } else {
                    ctx.sendMessage(Message.raw("Citizen entity not found in world. Spawn the citizen first."));
                }
            });
        }

        /**
         * Finds the citizen entity and updates their job component.
         * Must be called on the world thread.
         */
        private boolean findAndUpdateCitizenJob(Store<EntityStore> store, UUID colonyId, UUID citizenId, JobType jobType) {
            if (CitizenComponent.getComponentType() == null || JobComponent.getComponentType() == null) {
                LOGGER.atWarning().log("CitizenComponent or JobComponent not registered");
                return false;
            }

            // Get the entity reference from ColonyService tracking
            Ref<EntityStore> entityRef = colonyService.getCitizenEntity(citizenId);
            if (entityRef == null) {
                LOGGER.atFine().log("No tracked entity for citizen %s", citizenId);
                return false;
            }

            // Verify the entity still exists and has a CitizenComponent
            CitizenComponent citizenComp = store.getComponent(entityRef, CitizenComponent.getComponentType());
            if (citizenComp == null) {
                LOGGER.atFine().log("Entity ref exists but CitizenComponent not found for %s", citizenId);
                // Entity may have been despawned, clean up tracking
                colonyService.unregisterCitizenEntity(citizenId);
                return false;
            }

            // Create or update JobComponent
            JobComponent existingJob = store.getComponent(entityRef, JobComponent.getComponentType());
            JobComponent newJob;

            if (existingJob != null) {
                // Update existing job
                newJob = new JobComponent(
                        jobType,
                        existingJob.getCurrentState(),
                        existingJob.getAssignedTaskId(),
                        existingJob.getExperiencePoints()
                );
            } else {
                // Create new job component
                newJob = new JobComponent(jobType, "IDLE", null, 0);
            }

            store.addComponent(entityRef, JobComponent.getComponentType(), newJob);

            // Add IdleTag for couriers so they can receive tasks
            if (jobType == JobType.COURIER && IdleTag.getComponentType() != null) {
                IdleTag idleTag = store.getComponent(entityRef, IdleTag.getComponentType());
                if (idleTag == null) {
                    store.addComponent(entityRef, IdleTag.getComponentType(), new IdleTag());
                }
            }

            LOGGER.atInfo().log("Assigned job %s to citizen %s", jobType.name(), citizenId.toString().substring(0, 8));
            return true;
        }
    }
}
