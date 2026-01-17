package com.excelsies.hycolonies.command;

import com.excelsies.hycolonies.colony.model.CitizenData;
import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Command for managing citizens in a colony.
 *
 * Usage:
 * - /citizen add <colony-id> <name> - Adds a citizen to a colony
 * - /citizen list <colony-id> - Lists all citizens in a colony
 *
 * Note: In Phase 1, citizens are data-only (no in-world entities yet).
 */
public class CitizenCommand extends CommandBase {

    private final ColonyService colonyService;

    public CitizenCommand(ColonyService colonyService) {
        super("citizen", "Manage citizens in colonies");
        this.setPermissionGroup(GameMode.Adventure);
        this.colonyService = colonyService;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        // Parse arguments from the input string
        String inputString = ctx.getInputString();
        String[] parts = inputString.split("\\s+");

        // parts[0] is the command name "citizen"
        if (parts.length < 2) {
            showUsage(ctx);
            return;
        }

        String action = parts[1].toLowerCase();

        switch (action) {
            case "add":
                handleAdd(ctx, parts);
                break;
            case "list":
                handleList(ctx, parts);
                break;
            default:
                showUsage(ctx);
                break;
        }
    }

    private void showUsage(CommandContext ctx) {
        ctx.sendMessage(Message.raw("Usage:"));
        ctx.sendMessage(Message.raw("  /citizen add <colony-id> <name> - Add a citizen"));
        ctx.sendMessage(Message.raw("  /citizen list <colony-id> - List citizens"));
    }

    private void handleAdd(CommandContext ctx, String[] parts) {
        if (parts.length < 4) {
            ctx.sendMessage(Message.raw("Usage: /citizen add <colony-id> <name>"));
            return;
        }

        // Parse colony ID
        String idStr = parts[2];
        UUID colonyId;
        try {
            colonyId = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            ctx.sendMessage(Message.raw("Invalid colony ID format."));
            return;
        }

        // Check colony exists
        var colonyOpt = colonyService.getColony(colonyId);
        if (colonyOpt.isEmpty()) {
            ctx.sendMessage(Message.raw("Colony not found."));
            return;
        }

        ColonyData colony = colonyOpt.get();

        // Get citizen name
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 3; i < parts.length; i++) {
            if (i > 3) nameBuilder.append(" ");
            nameBuilder.append(parts[i]);
        }
        String citizenName = nameBuilder.toString();

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

    private void handleList(CommandContext ctx, String[] parts) {
        if (parts.length < 3) {
            ctx.sendMessage(Message.raw("Usage: /citizen list <colony-id>"));
            return;
        }

        // Parse colony ID
        String idStr = parts[2];
        UUID colonyId;
        try {
            colonyId = UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            ctx.sendMessage(Message.raw("Invalid colony ID format."));
            return;
        }

        var colonyOpt = colonyService.getColony(colonyId);
        if (colonyOpt.isEmpty()) {
            ctx.sendMessage(Message.raw("Colony not found."));
            return;
        }

        ColonyData colony = colonyOpt.get();

        if (colony.getPopulation() == 0) {
            ctx.sendMessage(Message.raw("Colony '" + colony.getName() + "' has no citizens yet."));
            ctx.sendMessage(Message.raw("Use /citizen add <colony-id> <name> to add one."));
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
