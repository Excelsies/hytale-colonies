package com.excelsies.hycolonies.command;

import com.excelsies.hycolonies.colony.model.CitizenData;
import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Command for managing citizens in a colony.
 *
 * Usage:
 * - /citizen add [colony-id] [name] - Adds a citizen to a colony
 * - /citizen list [colony-id] - Lists all citizens in a colony
 *
 * Note: In Phase 1, citizens are data-only (no in-world entities yet).
 */
public class CitizenCommand extends CommandBase {

    public CitizenCommand(ColonyService colonyService) {
        super("citizen", "Manage citizens in colonies");
        this.setPermissionGroup(GameMode.Adventure);

        // Register subcommands
        addSubCommand(new AddSubCommand(colonyService));
        addSubCommand(new ListSubCommand(colonyService));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        // Show usage when base command is called without subcommand
        ctx.sendMessage(Message.raw("Usage:"));
        ctx.sendMessage(Message.raw("  /citizen add [colony-id] [name] - Add a citizen"));
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
            super("add", "Add a citizen to a colony");
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony-id", "UUID of the colony", ArgTypes.UUID);
            this.nameArg = withRequiredArg("name", "Name of the citizen", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            UUID colonyId = ctx.get(colonyIdArg);
            String citizenName = ctx.get(nameArg);

            // Check colony exists
            var colonyOpt = colonyService.getColony(colonyId);
            if (colonyOpt.isEmpty()) {
                ctx.sendMessage(Message.raw("Colony not found."));
                return;
            }

            ColonyData colony = colonyOpt.get();

            // Add citizen
            CitizenData citizen = colonyService.addCitizen(
                    colonyId,
                    citizenName,
                    0, 64, 0  // Placeholder position
            );

            if (citizen != null) {
                ctx.sendMessage(Message.raw("Added citizen '" + citizenName + "' to " + colony.getName() + "!"));
                ctx.sendMessage(Message.raw("Colony population: " + colony.getPopulation()));
            } else {
                ctx.sendMessage(Message.raw("Failed to add citizen."));
            }
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
