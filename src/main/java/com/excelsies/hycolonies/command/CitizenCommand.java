package com.excelsies.hycolonies.command;

import com.excelsies.hycolonies.colony.model.CitizenData;
import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.colony.service.SkinGenerator;
import com.excelsies.hycolonies.ecs.component.CitizenComponent;
import com.excelsies.hycolonies.ecs.component.JobComponent;
import com.excelsies.hycolonies.ecs.component.JobType;
import com.excelsies.hycolonies.ecs.tag.IdleTag;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.npc.INonPlayerCharacter;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import it.unimi.dsi.fastutil.Pair;

import javax.annotation.Nonnull;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
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
    private static final Gson GSON = new Gson();

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
        ctx.sendMessage(Message.raw("  /citizen spawn [colony] [citizen] [skin] [job] - Spawn existing citizen"));
        ctx.sendMessage(Message.raw("  /citizen remove [colony] [citizen] - Remove a citizen"));
        ctx.sendMessage(Message.raw("  /citizen job [colony] [citizen] [job] - Assign a job to a citizen"));
        ctx.sendMessage(Message.raw("Note: Colony and citizen can be specified by name or UUID."));
        ctx.sendMessage(Message.raw("      Skin and job are optional."));
        ctx.sendMessage(Message.raw("      Jobs: " + String.join(", ",
                java.util.Arrays.stream(JobType.values())
                        .map(j -> j.name().toLowerCase())
                        .toArray(String[]::new))));
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
                                citizen.getNpcSkin(),
                                citizen.getSkinCosmetics()
                        );
                        store.addComponent(entityRef, CitizenComponent.getComponentType(), citizenComponent);
                    }

                    // Apply cosmetics if available
                    if (citizen.getSkinCosmetics() != null && !citizen.getSkinCosmetics().isEmpty()) {
                        try {
                            Type type = new TypeToken<Map<String, String>>(){}.getType();
                            Map<String, String> cosmetics = GSON.fromJson(citizen.getSkinCosmetics(), type);

                            if (cosmetics != null && PlayerSkinComponent.getComponentType() != null) {
                                PlayerSkin skin = new PlayerSkin();
                                
                                // Initialize ALL fields to safe defaults using constants
                                skin.bodyCharacteristic = SkinGenerator.DEFAULT_BODY;
                                skin.underwear = SkinGenerator.DEFAULT_UNDERWEAR;
                                skin.face = SkinGenerator.DEFAULT_FACE;
                                skin.eyes = SkinGenerator.DEFAULT_EYES;
                                skin.eyebrows = SkinGenerator.DEFAULT_EYEBROWS;
                                skin.mouth = SkinGenerator.DEFAULT_MOUTH;
                                skin.ears = SkinGenerator.DEFAULT_EARS;
                                skin.haircut = SkinGenerator.DEFAULT_HAIRCUT;
                                skin.pants = SkinGenerator.DEFAULT_PANTS;
                                skin.overtop = SkinGenerator.DEFAULT_OVERTOP;
                                skin.shoes = SkinGenerator.DEFAULT_SHOES;
                                
                                skin.undertop = "";
                                skin.overpants = "";
                                skin.cape = "";
                                skin.headAccessory = "";
                                skin.faceAccessory = "";
                                skin.earAccessory = "";
                                skin.facialHair = "";
                                skin.gloves = "";
                                skin.skinFeature = "";
                                
                                // Override with generated cosmetics if present and valid
                                if (cosmetics.containsKey(SkinGenerator.KEY_BODY) && cosmetics.get(SkinGenerator.KEY_BODY) != null) skin.bodyCharacteristic = cosmetics.get(SkinGenerator.KEY_BODY);
                                if (cosmetics.containsKey(SkinGenerator.KEY_UNDERWEAR) && cosmetics.get(SkinGenerator.KEY_UNDERWEAR) != null) skin.underwear = cosmetics.get(SkinGenerator.KEY_UNDERWEAR);
                                
                                if (cosmetics.containsKey(SkinGenerator.KEY_HAIR) && cosmetics.get(SkinGenerator.KEY_HAIR) != null) skin.haircut = cosmetics.get(SkinGenerator.KEY_HAIR);
                                if (cosmetics.containsKey(SkinGenerator.KEY_FACE) && cosmetics.get(SkinGenerator.KEY_FACE) != null) skin.face = cosmetics.get(SkinGenerator.KEY_FACE);
                                if (cosmetics.containsKey(SkinGenerator.KEY_EYES) && cosmetics.get(SkinGenerator.KEY_EYES) != null) skin.eyes = cosmetics.get(SkinGenerator.KEY_EYES);
                                if (cosmetics.containsKey(SkinGenerator.KEY_EYEBROWS) && cosmetics.get(SkinGenerator.KEY_EYEBROWS) != null) skin.eyebrows = cosmetics.get(SkinGenerator.KEY_EYEBROWS);
                                if (cosmetics.containsKey(SkinGenerator.KEY_MOUTH) && cosmetics.get(SkinGenerator.KEY_MOUTH) != null) skin.mouth = cosmetics.get(SkinGenerator.KEY_MOUTH);
                                if (cosmetics.containsKey(SkinGenerator.KEY_EARS) && cosmetics.get(SkinGenerator.KEY_EARS) != null) skin.ears = cosmetics.get(SkinGenerator.KEY_EARS);
                                
                                if (cosmetics.containsKey(SkinGenerator.KEY_PANTS) && cosmetics.get(SkinGenerator.KEY_PANTS) != null) skin.pants = cosmetics.get(SkinGenerator.KEY_PANTS);
                                if (cosmetics.containsKey(SkinGenerator.KEY_OVERTOP) && cosmetics.get(SkinGenerator.KEY_OVERTOP) != null) skin.overtop = cosmetics.get(SkinGenerator.KEY_OVERTOP);
                                if (cosmetics.containsKey(SkinGenerator.KEY_UNDERTOP) && cosmetics.get(SkinGenerator.KEY_UNDERTOP) != null) skin.undertop = cosmetics.get(SkinGenerator.KEY_UNDERTOP);
                                if (cosmetics.containsKey(SkinGenerator.KEY_SHOES) && cosmetics.get(SkinGenerator.KEY_SHOES) != null) skin.shoes = cosmetics.get(SkinGenerator.KEY_SHOES);
                                if (cosmetics.containsKey(SkinGenerator.KEY_OVERPANTS) && cosmetics.get(SkinGenerator.KEY_OVERPANTS) != null) skin.overpants = cosmetics.get(SkinGenerator.KEY_OVERPANTS);
                                
                                if (cosmetics.containsKey(SkinGenerator.KEY_CAPE) && cosmetics.get(SkinGenerator.KEY_CAPE) != null) skin.cape = cosmetics.get(SkinGenerator.KEY_CAPE);
                                if (cosmetics.containsKey(SkinGenerator.KEY_HEAD_ACCESSORY) && cosmetics.get(SkinGenerator.KEY_HEAD_ACCESSORY) != null) skin.headAccessory = cosmetics.get(SkinGenerator.KEY_HEAD_ACCESSORY);
                                if (cosmetics.containsKey(SkinGenerator.KEY_FACE_ACCESSORY) && cosmetics.get(SkinGenerator.KEY_FACE_ACCESSORY) != null) skin.faceAccessory = cosmetics.get(SkinGenerator.KEY_FACE_ACCESSORY);
                                if (cosmetics.containsKey(SkinGenerator.KEY_EAR_ACCESSORY) && cosmetics.get(SkinGenerator.KEY_EAR_ACCESSORY) != null) skin.earAccessory = cosmetics.get(SkinGenerator.KEY_EAR_ACCESSORY);
                                if (cosmetics.containsKey(SkinGenerator.KEY_FACIAL_HAIR) && cosmetics.get(SkinGenerator.KEY_FACIAL_HAIR) != null) skin.facialHair = cosmetics.get(SkinGenerator.KEY_FACIAL_HAIR);
                                if (cosmetics.containsKey(SkinGenerator.KEY_GLOVES) && cosmetics.get(SkinGenerator.KEY_GLOVES) != null) skin.gloves = cosmetics.get(SkinGenerator.KEY_GLOVES);

                                store.addComponent(entityRef, PlayerSkinComponent.getComponentType(), new PlayerSkinComponent(skin));
                                LOGGER.atFine().log("Applied cosmetics to citizen %s", citizen.getName());
                            }
                        } catch (Exception e) {
                            LOGGER.atWarning().log("Failed to apply cosmetics to citizen %s: %s", citizen.getName(), e.getMessage());
                        }
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

    // Subcommands (Add, List, Spawn, Remove, Job) follow - unchanged structure
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
            if (colony == null) return;

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

            String npcSkin;
            if (requestedSkin != null && !requestedSkin.isEmpty()) {
                String foundSkin = colony.getFaction().findSkin(requestedSkin);
                if (foundSkin != null) {
                    npcSkin = foundSkin;
                } else {
                    ctx.sendMessage(Message.raw("Skin '" + requestedSkin + "' not found in " + colony.getFaction().getDisplayName() + " faction."));
                    return;
                }
            } else {
                npcSkin = colony.getFaction().getRandomSkin();
                if (npcSkin == null) npcSkin = "Kweebec_Razorleaf";
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
            if (colony == null) return;

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

    private static class SpawnSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<String> colonyIdArg;
        private final RequiredArg<String> citizenIdArg;
        private final OptionalArg<String> skinArg;
        private final OptionalArg<String> jobArg;

        public SpawnSubCommand(ColonyService colonyService) {
            super("spawn", "Spawn an existing citizen as an NPC entity");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony", "Colony name or UUID", ArgTypes.STRING);
            this.citizenIdArg = withRequiredArg("citizen", "Citizen name or UUID", ArgTypes.STRING);
            this.skinArg = withOptionalArg("skin", "Override skin variant (optional)", ArgTypes.STRING);
            this.jobArg = withOptionalArg("job", "Assign job on spawn (optional)", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);
            String citizenIdentifier = ctx.get(citizenIdArg);
            String requestedSkin = ctx.get(skinArg);
            String requestedJob = ctx.get(jobArg);

            ColonyData colony = resolveColonyWithFeedback(colonyService, ctx, colonyIdentifier);
            if (colony == null) return;

            UUID colonyId = colony.getColonyId();
            CitizenData citizen = resolveCitizenWithFeedback(colonyService, ctx, colony, citizenIdentifier);
            if (citizen == null) return;

            // Parse job type if provided
            JobType jobType = null;
            if (requestedJob != null && !requestedJob.isEmpty()) {
                try {
                    jobType = JobType.valueOf(requestedJob.toUpperCase());
                } catch (IllegalArgumentException e) {
                    ctx.sendMessage(Message.raw("Unknown job type: " + requestedJob));
                    ctx.sendMessage(Message.raw("Available jobs: " + String.join(", ",
                            java.util.Arrays.stream(JobType.values())
                                    .map(j -> j.name().toLowerCase())
                                    .toArray(String[]::new))));
                    return;
                }
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

            String npcSkin;
            if (requestedSkin != null && !requestedSkin.isEmpty()) {
                String foundSkin = colony.getFaction().findSkin(requestedSkin);
                if (foundSkin != null) {
                    npcSkin = foundSkin;
                    citizen.setNpcSkin(foundSkin);
                } else {
                    ctx.sendMessage(Message.raw("Skin '" + requestedSkin + "' not found in " + colony.getFaction().getDisplayName() + " faction."));
                    return;
                }
            } else {
                npcSkin = citizen.getNpcSkin();
            }

            final String finalNpcSkin = npcSkin;
            final JobType finalJobType = jobType;

            world.execute(() -> {
                try {
                    Store<EntityStore> store = playerRef.getStore();
                    if (store == null) {
                        ctx.sendMessage(Message.raw("Could not get entity store."));
                        return;
                    }

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
                        StringBuilder message = new StringBuilder();
                        message.append("Spawned citizen '").append(citizen.getName())
                               .append("' (").append(finalNpcSkin).append(") at ")
                               .append((int) x).append(", ").append((int) y).append(", ").append((int) z);

                        // Apply job if requested
                        if (finalJobType != null) {
                            Ref<EntityStore> entityRef = colonyService.getCitizenEntity(citizen.getCitizenId());
                            if (entityRef != null && JobComponent.getComponentType() != null) {
                                JobComponent jobComponent = new JobComponent(finalJobType, "IDLE", null, 0);
                                store.addComponent(entityRef, JobComponent.getComponentType(), jobComponent);

                                // Add IdleTag for employed citizens
                                if (finalJobType != JobType.UNEMPLOYED && IdleTag.getComponentType() != null) {
                                    store.addComponent(entityRef, IdleTag.getComponentType(), new IdleTag());
                                }

                                message.append(" as ").append(finalJobType.getDisplayName());
                                LOGGER.atInfo().log("Assigned job %s to citizen %s on spawn",
                                        finalJobType.name(), citizen.getCitizenId().toString().substring(0, 8));
                            }
                        }

                        ctx.sendMessage(Message.raw(message.toString()));
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
            if (colony == null) return;

            CitizenData citizen = resolveCitizenWithFeedback(colonyService, ctx, colony, citizenIdentifier);
            if (citizen == null) return;

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
            this.jobTypeArg = withRequiredArg("job", "Job type", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);
            String citizenIdentifier = ctx.get(citizenIdArg);
            String jobTypeStr = ctx.get(jobTypeArg);

            ColonyData colony = resolveColonyWithFeedback(colonyService, ctx, colonyIdentifier);
            if (colony == null) return;

            CitizenData citizen = resolveCitizenWithFeedback(colonyService, ctx, colony, citizenIdentifier);
            if (citizen == null) return;

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
            if (player == null || player.getWorld() == null) return;

            World world = player.getWorld();
            UUID colonyId = colony.getColonyId();
            UUID citizenId = citizen.getCitizenId();

            world.execute(() -> {
                Store<EntityStore> store = world.getEntityStore().getStore();
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
            if (CitizenComponent.getComponentType() == null || JobComponent.getComponentType() == null) return false;

            Ref<EntityStore> entityRef = colonyService.getCitizenEntity(citizenId);
            if (entityRef == null) return false;

            CitizenComponent citizenComp = store.getComponent(entityRef, CitizenComponent.getComponentType());
            if (citizenComp == null) {
                colonyService.unregisterCitizenEntity(citizenId);
                return false;
            }

            JobComponent existingJob = store.getComponent(entityRef, JobComponent.getComponentType());
            JobComponent newJob;

            if (existingJob != null) {
                newJob = new JobComponent(
                        jobType,
                        existingJob.getCurrentState(),
                        existingJob.getAssignedTaskId(),
                        existingJob.getExperiencePoints()
                );
            } else {
                newJob = new JobComponent(jobType, "IDLE", null, 0);
            }

            store.addComponent(entityRef, JobComponent.getComponentType(), newJob);

            // Add IdleTag for all job types (except UNEMPLOYED) to mark them as available for tasks
            if (jobType != JobType.UNEMPLOYED && IdleTag.getComponentType() != null) {
                IdleTag idleTag = store.getComponent(entityRef, IdleTag.getComponentType());
                if (idleTag == null) {
                    store.addComponent(entityRef, IdleTag.getComponentType(), new IdleTag());
                }
            } else if (jobType == JobType.UNEMPLOYED && IdleTag.getComponentType() != null) {
                // Remove IdleTag if setting to UNEMPLOYED
                IdleTag idleTag = store.getComponent(entityRef, IdleTag.getComponentType());
                if (idleTag != null) {
                    store.removeComponent(entityRef, IdleTag.getComponentType());
                }
            }

            LOGGER.atInfo().log("Assigned job %s to citizen %s", jobType.name(), citizenId.toString().substring(0, 8));
            return true;
        }
    }
}