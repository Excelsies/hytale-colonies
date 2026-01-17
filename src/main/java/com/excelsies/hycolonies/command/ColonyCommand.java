package com.excelsies.hycolonies.command;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.UUID;

/**
 * Command for creating and managing colonies.
 *
 * Usage:
 * - /colony create <name> - Creates a new colony
 * - /colony list - Lists all colonies
 * - /colony info <id> - Shows colony info
 * - /colony save - Saves all colony data
 */
public class ColonyCommand extends CommandBase {

    private final ColonyService colonyService;

    public ColonyCommand(ColonyService colonyService) {
        super("colony", "Create and manage colonies");
        this.setPermissionGroup(GameMode.Adventure);
        this.colonyService = colonyService;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        // Parse arguments from the input string
        String inputString = ctx.getInputString();
        String[] parts = inputString.split("\\s+");

        // parts[0] is the command name "colony"
        if (parts.length < 2) {
            showUsage(ctx);
            return;
        }

        String action = parts[1].toLowerCase();

        switch (action) {
            case "create":
                handleCreate(ctx, parts);
                break;
            case "list":
                handleList(ctx);
                break;
            case "info":
                handleInfo(ctx, parts);
                break;
            case "save":
                handleSave(ctx);
                break;
            default:
                showUsage(ctx);
                break;
        }
    }

    private void showUsage(CommandContext ctx) {
        ctx.sendMessage(Message.raw("Usage:"));
        ctx.sendMessage(Message.raw("  /colony create <name> - Create a new colony"));
        ctx.sendMessage(Message.raw("  /colony list - List all colonies"));
        ctx.sendMessage(Message.raw("  /colony info <id> - Show colony info"));
        ctx.sendMessage(Message.raw("  /colony save - Save all colony data"));
    }

    private void handleCreate(CommandContext ctx, String[] parts) {
        // Get colony name from arguments
        String name;
        if (parts.length > 2) {
            StringBuilder nameBuilder = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) nameBuilder.append(" ");
                nameBuilder.append(parts[i]);
            }
            name = nameBuilder.toString();
        } else {
            ctx.sendMessage(Message.raw("Usage: /colony create <name>"));
            return;
        }

        // Create colony with placeholder owner (no player access in base API)
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
            ctx.sendMessage(Message.raw("Use /citizen add <colony-id> <name> to add citizens."));
        } else {
            ctx.sendMessage(Message.raw("Failed to create colony."));
        }
    }

    private void handleList(CommandContext ctx) {
        Collection<ColonyData> colonies = colonyService.getAllColonies();

        if (colonies.isEmpty()) {
            ctx.sendMessage(Message.raw("No colonies exist yet."));
            ctx.sendMessage(Message.raw("Use /colony create <name> to create one."));
            return;
        }

        ctx.sendMessage(Message.raw("=== Colonies ==="));
        for (ColonyData colony : colonies) {
            ctx.sendMessage(Message.raw("  " + colony.getName() +
                    " (ID: " + colony.getColonyId().toString().substring(0, 8) + "...) " +
                    "Pop: " + colony.getPopulation()));
        }
    }

    private void handleInfo(CommandContext ctx, String[] parts) {
        if (parts.length < 3) {
            ctx.sendMessage(Message.raw("Usage: /colony info <colony-id>"));
            return;
        }

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

    private void handleSave(CommandContext ctx) {
        colonyService.saveAll();
        ctx.sendMessage(Message.raw("All colonies saved!"));
    }
}
