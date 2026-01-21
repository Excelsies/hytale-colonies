package com.excelsies.hycolonies.command;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.logistics.model.RequestPriority;
import com.excelsies.hycolonies.logistics.model.RequestType;
import com.excelsies.hycolonies.logistics.model.TransportInstruction;
import com.excelsies.hycolonies.logistics.service.InventoryCacheService;
import com.excelsies.hycolonies.logistics.service.LogisticsService;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * Debug command for the logistics system.
 *
 * Usage:
 * - /logistics status - Show solver status
 * - /logistics requests - List pending requests
 * - /logistics test [colony] [item] - Create a test request
 */
public class LogisticsCommand extends CommandBase {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public LogisticsCommand(LogisticsService logisticsService, InventoryCacheService inventoryCache,
                            ColonyService colonyService) {
        super("logistics", "Debug logistics system");
        this.setPermissionGroup(GameMode.Creative);

        addSubCommand(new StatusSubCommand(logisticsService, inventoryCache));
        addSubCommand(new RequestsSubCommand(logisticsService));
        addSubCommand(new TestSubCommand(logisticsService, colonyService));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw("Usage:"));
        ctx.sendMessage(Message.raw("  /logistics status - Show system status"));
        ctx.sendMessage(Message.raw("  /logistics requests - List active instructions"));
        ctx.sendMessage(Message.raw("  /logistics test [colony] [item] - Create test request"));
    }

    // =====================
    // Subcommand: status
    // =====================
    private static class StatusSubCommand extends CommandBase {
        private final LogisticsService logisticsService;
        private final InventoryCacheService inventoryCache;

        public StatusSubCommand(LogisticsService logisticsService, InventoryCacheService inventoryCache) {
            super("status", "Show logistics system status");
            this.logisticsService = logisticsService;
            this.inventoryCache = inventoryCache;
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            ctx.sendMessage(Message.raw("=== Logistics System Status ==="));

            // Solver status
            ctx.sendMessage(Message.raw("Solver: " +
                    (logisticsService.isSolveInProgress() ? "Running" : "Idle")));
            ctx.sendMessage(Message.raw("Last solve time: " +
                    logisticsService.getLastSolveTimeMs() + "ms"));

            // Queue status
            ctx.sendMessage(Message.raw(""));
            ctx.sendMessage(Message.raw("Queues:"));
            ctx.sendMessage(Message.raw("  Pending requests: " +
                    logisticsService.getPendingRequestCount()));
            ctx.sendMessage(Message.raw("  Pending instructions: " +
                    logisticsService.getPendingInstructionCount()));
            ctx.sendMessage(Message.raw("  Active instructions: " +
                    logisticsService.getActiveInstructionCount()));

            // Statistics
            ctx.sendMessage(Message.raw(""));
            ctx.sendMessage(Message.raw("Statistics:"));
            ctx.sendMessage(Message.raw("  Total requests processed: " +
                    logisticsService.getTotalRequestsProcessed()));
            ctx.sendMessage(Message.raw("  Total instructions generated: " +
                    logisticsService.getTotalInstructionsGenerated()));

            // Cache status
            ctx.sendMessage(Message.raw(""));
            ctx.sendMessage(Message.raw("Inventory Cache:"));
            ctx.sendMessage(Message.raw("  Cached warehouses: " +
                    inventoryCache.getTotalWarehouseCount()));
        }
    }

    // =====================
    // Subcommand: requests
    // =====================
    private static class RequestsSubCommand extends CommandBase {
        private final LogisticsService logisticsService;

        public RequestsSubCommand(LogisticsService logisticsService) {
            super("requests", "List active transport instructions");
            this.logisticsService = logisticsService;
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            Collection<TransportInstruction> active = logisticsService.getActiveInstructions();

            if (active.isEmpty()) {
                ctx.sendMessage(Message.raw("No active transport instructions."));
                return;
            }

            ctx.sendMessage(Message.raw("=== Active Instructions (" + active.size() + ") ==="));

            int index = 1;
            for (TransportInstruction inst : active) {
                ctx.sendMessage(Message.raw(index + ". " + inst.itemId() + " x" + inst.quantity()));
                ctx.sendMessage(Message.raw("   From: " + inst.source().position()));
                ctx.sendMessage(Message.raw("   To: " + inst.destination().position()));
                ctx.sendMessage(Message.raw("   Priority: " + inst.priority()));
                ctx.sendMessage(Message.raw("   Age: " + (inst.getAgeMs() / 1000) + "s"));
                index++;
            }
        }
    }

    // =====================
    // Subcommand: test
    // =====================
    private static class TestSubCommand extends CommandBase {
        private final LogisticsService logisticsService;
        private final ColonyService colonyService;
        private final RequiredArg<String> colonyIdArg;
        private final RequiredArg<String> itemIdArg;

        public TestSubCommand(LogisticsService logisticsService, ColonyService colonyService) {
            super("test", "Create a test transport request");
            this.logisticsService = logisticsService;
            this.colonyService = colonyService;
            this.colonyIdArg = withRequiredArg("colony", "Colony name or UUID", ArgTypes.STRING);
            this.itemIdArg = withRequiredArg("item", "Item ID to request", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);
            String itemId = ctx.get(itemIdArg);

            var result = colonyService.resolveColony(colonyIdentifier);
            if (result.isNotFound()) {
                ctx.sendMessage(Message.raw("Colony not found: " + colonyIdentifier));
                return;
            }
            if (result.hasMultipleMatches()) {
                ctx.sendMessage(Message.raw("Multiple colonies found. Please specify UUID."));
                return;
            }

            ColonyData colony = result.getColony();

            if (!ctx.isPlayer()) {
                ctx.sendMessage(Message.raw("This command must be run by a player."));
                return;
            }

            Player player = ctx.senderAs(Player.class);
            if (player == null || player.getWorld() == null) {
                ctx.sendMessage(Message.raw("Could not get player world."));
                return;
            }

            var world = player.getWorld();
            var playerRef = player.getReference();

            if (playerRef == null) {
                ctx.sendMessage(Message.raw("Could not get player reference."));
                return;
            }

            // All store operations must run on the world thread
            world.execute(() -> {
                // Get player position for destination
                Vector3i destination;
                var store = playerRef.getStore();
                var transform = store.getComponent(playerRef,
                        com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
                if (transform != null && transform.getPosition() != null) {
                    destination = new Vector3i(
                            (int) transform.getPosition().getX(),
                            (int) transform.getPosition().getY(),
                            (int) transform.getPosition().getZ()
                    );
                } else {
                    destination = new Vector3i(0, 64, 0);
                }

                // Create test request
                var request = logisticsService.createRequest(
                        colony.getColonyId(),
                        itemId,
                        1,
                        destination,
                        RequestPriority.NORMAL,
                        RequestType.MANUAL
                );

                ctx.sendMessage(Message.raw("Created test request:"));
                ctx.sendMessage(Message.raw("  Item: " + itemId));
                ctx.sendMessage(Message.raw("  Quantity: 1"));
                ctx.sendMessage(Message.raw("  Destination: " + destination));
                ctx.sendMessage(Message.raw("  Colony: " + colony.getName()));
                ctx.sendMessage(Message.raw("  Request ID: " + request.requestId().toString().substring(0, 8)));
            });
        }
    }
}
