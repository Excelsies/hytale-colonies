package com.excelsies.hycolonies.command;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.UUID;

/**
 * Command for creating and managing colonies.
 *
 * Usage:
 * - /colony create [name] [faction] - Creates a new colony
 * - /colony list - Lists all colonies
 * - /colony info [id] - Shows colony info
 * - /colony save - Saves all colony data
 * - /colony delete [id] - Deletes a colony and all its citizens
 */
public class ColonyCommand extends CommandBase {

    public ColonyCommand(ColonyService colonyService) {
        super("colony", "Create and manage colonies");
        this.setPermissionGroup(GameMode.Adventure);

        // Register subcommands
        addSubCommand(new CreateSubCommand(colonyService));
        addSubCommand(new ListSubCommand(colonyService));
        addSubCommand(new InfoSubCommand(colonyService));
        addSubCommand(new SaveSubCommand(colonyService));
        addSubCommand(new DeleteSubCommand(colonyService));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        // Show usage when base command is called without subcommand
        ctx.sendMessage(Message.raw("Usage:"));
        ctx.sendMessage(Message.raw("  /colony create [name] [faction] - Create a new colony"));
        ctx.sendMessage(Message.raw("  /colony list - List all colonies"));
        ctx.sendMessage(Message.raw("  /colony info [id] - Show colony info"));
        ctx.sendMessage(Message.raw("  /colony save - Save all colony data"));
        ctx.sendMessage(Message.raw("  /colony delete [id] - Delete a colony and all citizens"));
    }

    // =====================
    // Subcommand: create
    // =====================
    private static class CreateSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<String> nameArg;
        private final RequiredArg<String> factionArg;

        public CreateSubCommand(ColonyService colonyService) {
            super("create", "Create a new colony");
            this.colonyService = colonyService;
            this.nameArg = withRequiredArg("name", "Name of the colony", ArgTypes.STRING);
            this.factionArg = withRequiredArg("faction", "Faction (Kweebec, Trork, Outlander, Goblin, Feran, Klops)", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String name = ctx.get(nameArg);
            String factionName = ctx.get(factionArg);

            com.excelsies.hycolonies.colony.model.Faction faction = com.excelsies.hycolonies.colony.model.Faction.fromString(factionName);
            if (faction == null) {
                ctx.sendMessage(Message.raw("Invalid faction: " + factionName));
                ctx.sendMessage(Message.raw("Valid factions: " +
                        java.util.Arrays.stream(com.excelsies.hycolonies.colony.model.Faction.values())
                                .map(com.excelsies.hycolonies.colony.model.Faction::getDisplayName)
                                .collect(java.util.stream.Collectors.joining(", "))));
                return;
            }

            // Get player position for colony center
            if (!ctx.isPlayer()) {
                ctx.sendMessage(Message.raw("This command must be run by a player."));
                return;
            }

            Player player = ctx.senderAs(Player.class);
            World world = player.getWorld();
            Ref<EntityStore> playerRef = player.getReference();

            if (world == null || playerRef == null) {
                ctx.sendMessage(Message.raw("Could not get player world or reference."));
                return;
            }

            UUID ownerUuid = player.getPlayerRef().getUuid();
            String worldId = world.getName() != null ? world.getName() : "default";

            // Execute on world thread to access store and get position
            world.execute(() -> {
                Store<EntityStore> store = playerRef.getStore();
                TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());

                double centerX = 0;
                double centerY = 64;
                double centerZ = 0;

                if (transform != null && transform.getPosition() != null) {
                    var pos = transform.getPosition();
                    centerX = pos.getX();
                    centerY = pos.getY();
                    centerZ = pos.getZ();
                }

                ColonyData colony = colonyService.createColony(
                        name,
                        ownerUuid,
                        centerX, centerY, centerZ,
                        worldId,
                        faction
                );

                if (colony != null) {
                    ctx.sendMessage(Message.raw("Colony '" + name + "' created successfully!"));
                    ctx.sendMessage(Message.raw("Faction: " + colony.getFaction().getDisplayName()));
                    ctx.sendMessage(Message.raw("Colony ID: " + colony.getColonyId()));
                    ctx.sendMessage(Message.raw("Center: (" + (int) centerX + ", " + (int) centerY + ", " + (int) centerZ + ")"));
                    ctx.sendMessage(Message.raw("Use /citizen add [colony-id] [name] to add citizens."));
                } else {
                    ctx.sendMessage(Message.raw("Failed to create colony."));
                }
            });
        }
    }

    // =====================
    // Subcommand: list
    // =====================
    private static class ListSubCommand extends CommandBase {
        private final ColonyService colonyService;

        public ListSubCommand(ColonyService colonyService) {
            super("list", "List all colonies");
            this.colonyService = colonyService;
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            Collection<ColonyData> colonies = colonyService.getAllColonies();

            if (colonies.isEmpty()) {
                ctx.sendMessage(Message.raw("No colonies exist yet."));
                ctx.sendMessage(Message.raw("Use /colony create [name] to create one."));
                return;
            }

            ctx.sendMessage(Message.raw("=== Colonies ==="));
            for (ColonyData colony : colonies) {
                ctx.sendMessage(Message.raw("  " + colony.getName() +
                        " [" + colony.getFaction().getDisplayName() + "] Pop: " + colony.getPopulation()));
                ctx.sendMessage(Message.raw("    ID: " + colony.getColonyId()));
            }
        }
    }

    // =====================
    // Subcommand: info
    // =====================
    private static class InfoSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<String> idArg;

        public InfoSubCommand(ColonyService colonyService) {
            super("info", "Show colony information");
            this.colonyService = colonyService;
            this.idArg = withRequiredArg("id", "Colony name or UUID", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String identifier = ctx.get(idArg);

            var result = colonyService.resolveColony(identifier);
            if (result.isNotFound()) {
                ctx.sendMessage(Message.raw("Colony not found: " + identifier));
                return;
            }
            if (result.hasMultipleMatches()) {
                ctx.sendMessage(Message.raw("Multiple colonies found with name '" + identifier + "'. Please specify UUID:"));
                for (ColonyData match : result.getMultipleMatches()) {
                    ctx.sendMessage(Message.raw("  - " + match.getName() + " [" + match.getFaction().getDisplayName() + "]"));
                    ctx.sendMessage(Message.raw("    ID: " + match.getColonyId()));
                }
                return;
            }

            ColonyData colony = result.getColony();
            ctx.sendMessage(Message.raw("=== " + colony.getName() + " ==="));
            ctx.sendMessage(Message.raw("ID: " + colony.getColonyId()));
            ctx.sendMessage(Message.raw("Faction: " + colony.getFaction().getDisplayName()));
            ctx.sendMessage(Message.raw("Population: " + colony.getPopulation() + " citizens"));
            ctx.sendMessage(Message.raw("Location: " +
                    (int) colony.getCenterX() + ", " +
                    (int) colony.getCenterY() + ", " +
                    (int) colony.getCenterZ()));

            if (colony.getPopulation() > 0) {
                ctx.sendMessage(Message.raw("Citizens:"));
                colony.getCitizens().forEach(citizen ->
                        ctx.sendMessage(Message.raw("  - " + citizen.getName() + " (" + citizen.getNpcSkin() + ")"))
                );
            }
        }
    }

    // =====================
    // Subcommand: save
    // =====================
    private static class SaveSubCommand extends CommandBase {
        private final ColonyService colonyService;

        public SaveSubCommand(ColonyService colonyService) {
            super("save", "Save all colony data");
            this.colonyService = colonyService;
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            colonyService.saveAll();
            ctx.sendMessage(Message.raw("All colonies saved!"));
        }
    }

    // =====================
    // Subcommand: delete
    // =====================
    private static class DeleteSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<String> idArg;

        public DeleteSubCommand(ColonyService colonyService) {
            super("delete", "Delete a colony and all its citizens");
            this.colonyService = colonyService;
            this.idArg = withRequiredArg("id", "Colony name or UUID", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String identifier = ctx.get(idArg);

            var result = colonyService.resolveColony(identifier);
            if (result.isNotFound()) {
                ctx.sendMessage(Message.raw("Colony not found: " + identifier));
                return;
            }
            if (result.hasMultipleMatches()) {
                ctx.sendMessage(Message.raw("Multiple colonies found with name '" + identifier + "'. Please specify UUID:"));
                for (ColonyData match : result.getMultipleMatches()) {
                    ctx.sendMessage(Message.raw("  - " + match.getName() + " [" + match.getFaction().getDisplayName() + "]"));
                    ctx.sendMessage(Message.raw("    ID: " + match.getColonyId()));
                }
                return;
            }

            ColonyData colony = result.getColony();
            String colonyName = colony.getName();
            int citizenCount = colony.getPopulation();

            ColonyData deleted = colonyService.deleteColony(colony.getColonyId());

            if (deleted != null) {
                ctx.sendMessage(Message.raw("Deleted colony '" + colonyName + "'."));
                if (citizenCount > 0) {
                    ctx.sendMessage(Message.raw("Removed " + citizenCount + " citizen(s) from records."));
                    ctx.sendMessage(Message.raw("Note: Spawned NPC entities remain in world until chunk unloads."));
                }
            } else {
                ctx.sendMessage(Message.raw("Failed to delete colony."));
            }
        }
    }
}
