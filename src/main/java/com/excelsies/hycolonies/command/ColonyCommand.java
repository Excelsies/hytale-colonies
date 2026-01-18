package com.excelsies.hycolonies.command;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.UUID;

/**
 * Command for creating and managing colonies.
 *
 * Usage:
 * - /colony create [name] - Creates a new colony
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
        ctx.sendMessage(Message.raw("  /colony create [name] - Create a new colony"));
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

        public CreateSubCommand(ColonyService colonyService) {
            super("create", "Create a new colony");
            this.colonyService = colonyService;
            this.nameArg = withRequiredArg("name", "Name of the colony", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String name = ctx.get(nameArg);

            // Create colony with placeholder owner (no player context available in base API)
            UUID placeholderOwner = UUID.randomUUID();
            ColonyData colony = colonyService.createColony(
                    name,
                    placeholderOwner,
                    0, 64, 0,  // Placeholder position
                    "default"
            );

            if (colony != null) {
                ctx.sendMessage(Message.raw("Colony '" + name + "' created successfully!"));
                ctx.sendMessage(Message.raw("Colony ID: " + colony.getColonyId()));
                ctx.sendMessage(Message.raw("Use /citizen add [colony-id] [name] to add citizens."));
            } else {
                ctx.sendMessage(Message.raw("Failed to create colony."));
            }
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
                        " (ID: " + colony.getColonyId().toString().substring(0, 8) + "...) " +
                        "Pop: " + colony.getPopulation()));
            }
        }
    }

    // =====================
    // Subcommand: info
    // =====================
    private static class InfoSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final RequiredArg<UUID> idArg;

        public InfoSubCommand(ColonyService colonyService) {
            super("info", "Show colony information");
            this.colonyService = colonyService;
            this.idArg = withRequiredArg("id", "Colony UUID", ArgTypes.UUID);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            UUID colonyId = ctx.get(idArg);

            var colonyOpt = colonyService.getColony(colonyId);
            if (colonyOpt.isEmpty()) {
                ctx.sendMessage(Message.raw("Colony not found."));
                return;
            }

            ColonyData colony = colonyOpt.get();
            ctx.sendMessage(Message.raw("=== " + colony.getName() + " ==="));
            ctx.sendMessage(Message.raw("ID: " + colony.getColonyId()));
            ctx.sendMessage(Message.raw("Population: " + colony.getPopulation() + " citizens"));
            ctx.sendMessage(Message.raw("Location: " +
                    (int) colony.getCenterX() + ", " +
                    (int) colony.getCenterY() + ", " +
                    (int) colony.getCenterZ()));

            if (colony.getPopulation() > 0) {
                ctx.sendMessage(Message.raw("Citizens:"));
                colony.getCitizens().forEach(citizen ->
                        ctx.sendMessage(Message.raw("  - " + citizen.getName()))
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
        private final RequiredArg<UUID> idArg;

        public DeleteSubCommand(ColonyService colonyService) {
            super("delete", "Delete a colony and all its citizens");
            this.colonyService = colonyService;
            this.idArg = withRequiredArg("id", "Colony UUID", ArgTypes.UUID);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            UUID colonyId = ctx.get(idArg);

            var colonyOpt = colonyService.getColony(colonyId);
            if (colonyOpt.isEmpty()) {
                ctx.sendMessage(Message.raw("Colony not found."));
                return;
            }

            ColonyData colony = colonyOpt.get();
            String colonyName = colony.getName();
            int citizenCount = colony.getPopulation();

            ColonyData deleted = colonyService.deleteColony(colonyId);

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
