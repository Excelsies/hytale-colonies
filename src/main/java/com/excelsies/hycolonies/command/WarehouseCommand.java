package com.excelsies.hycolonies.command;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.excelsies.hycolonies.colony.service.ColonyService;
import com.excelsies.hycolonies.logistics.event.InventoryChangeHandler;
import com.excelsies.hycolonies.logistics.service.InventoryCacheService;
import com.excelsies.hycolonies.warehouse.WarehouseData;
import com.excelsies.hycolonies.warehouse.WarehouseRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.math.util.ChunkUtil;

import com.excelsies.hycolonies.logistics.model.ItemEntry;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Command for managing warehouses in colonies.
 *
 * Usage:
 * - /warehouse register [colony] - Register the block at crosshair as a warehouse
 * - /warehouse list [colony] - List all warehouses in a colony
 * - /warehouse unregister [colony] - Remove the warehouse at crosshair
 */
public class WarehouseCommand extends CommandBase {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double DEFAULT_COLONY_RADIUS = 100.0;

    public WarehouseCommand(ColonyService colonyService, WarehouseRegistry warehouseRegistry,
                            InventoryCacheService inventoryCache, InventoryChangeHandler changeHandler) {
        super("warehouse", "Manage colony warehouses");
        this.setPermissionGroup(GameMode.Adventure);

        addSubCommand(new RegisterSubCommand(colonyService, warehouseRegistry, inventoryCache, changeHandler));
        addSubCommand(new ListSubCommand(colonyService));
        addSubCommand(new UnregisterSubCommand(colonyService, warehouseRegistry, inventoryCache, changeHandler));
        addSubCommand(new StockSubCommand(colonyService, inventoryCache));
        addSubCommand(new ToggleSubCommand(colonyService, warehouseRegistry, inventoryCache, changeHandler));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        ctx.sendMessage(Message.raw("Usage:"));
        ctx.sendMessage(Message.raw("  /warehouse toggle - Toggle registration of looked-at container"));
        ctx.sendMessage(Message.raw("  /warehouse register [colony] - Register block at crosshair"));
        ctx.sendMessage(Message.raw("  /warehouse list [colony] - List warehouses"));
        ctx.sendMessage(Message.raw("  /warehouse unregister [colony] - Remove warehouse at crosshair"));
        ctx.sendMessage(Message.raw("  /warehouse stock [colony] [item] [quantity] - Add test items to warehouse"));
    }

    /**
     * Helper to resolve a colony and send error messages if needed.
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
     * Gets the block position the player is looking at.
     * This is a simplified version - in production, you'd use raycast.
     * MUST be called from world thread.
     */
    private static Vector3i getTargetBlock(Store<EntityStore> store, Ref<EntityStore> ref) {
        // For now, use the block in front of the player at their feet level
        // A proper implementation would use raycasting
        var transform = store.getComponent(ref, TransformComponent.getComponentType());

        if (transform == null || transform.getPosition() == null) {
            return null;
        }

        // Get position slightly in front of player
        var pos = transform.getPosition();
        var rot = transform.getRotation();

        // Simple approximation: 2 blocks in front
        double yaw = Math.toRadians(rot.getY());
        int targetX = (int) Math.floor(pos.getX() - Math.sin(yaw) * 2);
        int targetY = (int) Math.floor(pos.getY());
        int targetZ = (int) Math.floor(pos.getZ() + Math.cos(yaw) * 2);

        return new Vector3i(targetX, targetY, targetZ);
    }
    
    /**
     * Finds the colony that contains the given block position.
     */
    private static ColonyData findColonyContainingPosition(ColonyService colonyService, Vector3i blockPos, String worldId) {
        for (ColonyData colony : colonyService.getAllColonies()) {
            if (!worldId.equals(colony.getWorldId())) {
                continue;
            }

            double centerX = colony.getCenterX();
            double centerY = colony.getCenterY();
            double centerZ = colony.getCenterZ();

            double dx = blockPos.getX() - centerX;
            double dy = blockPos.getY() - centerY;
            double dz = blockPos.getZ() - centerZ;
            double distanceSquared = dx * dx + dy * dy + dz * dz;

            double radiusSquared = DEFAULT_COLONY_RADIUS * DEFAULT_COLONY_RADIUS;

            if (distanceSquared <= radiusSquared) {
                return colony;
            }
        }
        return null;
    }

    // =====================
    // Subcommand: register
    // =====================
    private static class RegisterSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final WarehouseRegistry warehouseRegistry;
        private final InventoryCacheService inventoryCache;
        private final InventoryChangeHandler changeHandler;
        private final RequiredArg<String> colonyIdArg;

        public RegisterSubCommand(ColonyService colonyService, WarehouseRegistry warehouseRegistry,
                                  InventoryCacheService inventoryCache, InventoryChangeHandler changeHandler) {
            super("register", "Register a warehouse for a colony");
            this.colonyService = colonyService;
            this.warehouseRegistry = warehouseRegistry;
            this.inventoryCache = inventoryCache;
            this.changeHandler = changeHandler;
            this.colonyIdArg = withRequiredArg("colony", "Colony name or UUID", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);

            ColonyData colony = resolveColonyWithFeedback(colonyService, ctx, colonyIdentifier);
            if (colony == null) {
                return;
            }

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

            UUID colonyId = colony.getColonyId();

            // Execute on world thread to access store
            world.execute(() -> {
                Store<EntityStore> store = playerRef.getStore();
                Vector3i targetPos = getTargetBlock(store, playerRef);

                if (targetPos == null) {
                    ctx.sendMessage(Message.raw("Could not determine target block position."));
                    return;
                }

                // Check if already registered
                if (colony.hasWarehouseAt(targetPos)) {
                    ctx.sendMessage(Message.raw("A warehouse is already registered at this position."));
                    return;
                }

                // Validate that the block is actually an item container before registering
                long chunkKey = ChunkUtil.indexChunkFromBlock(targetPos.getX(), targetPos.getZ());
                WorldChunk chunk = world.getChunkIfLoaded(chunkKey);

                if (chunk == null) {
                    ctx.sendMessage(Message.raw("Could not load chunk at target position."));
                    return;
                }

                int localX = ChunkUtil.localCoordinate(targetPos.getX());
                int localY = targetPos.getY();
                int localZ = ChunkUtil.localCoordinate(targetPos.getZ());

                var blockState = chunk.getState(localX, localY, localZ);

                if (!(blockState instanceof ItemContainerBlockState containerState)) {
                    ctx.sendMessage(Message.raw("Block at target position is not a container."));
                    ctx.sendMessage(Message.raw("Look at a chest or other container block and try again."));
                    return;
                }

                // Verify the container exists
                if (containerState.getItemContainer() == null) {
                    ctx.sendMessage(Message.raw("Block has no inventory. Please look at a valid container."));
                    return;
                }

                // Determine block type and capacity from the actual container
                String blockType = "Container";
                int capacity = containerState.getItemContainer().getCapacity();

                // Register the warehouse
                String worldId = "default";

                WarehouseData warehouseData = new WarehouseData(targetPos, blockType, capacity, worldId);
                colony.addWarehouse(warehouseData);

                // Update registry and cache
                warehouseRegistry.registerWarehouse(colonyId, targetPos);
                inventoryCache.registerWarehouse(colonyId, targetPos);

                // Register event listener for this container (event-driven cache updates)
                changeHandler.registerContainerListener(colonyId, targetPos, world);

                ctx.sendMessage(Message.raw("Registered warehouse at (" +
                        targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + ")"));
                ctx.sendMessage(Message.raw("Container type: " + blockType + " (capacity: " + capacity + " slots)"));
                ctx.sendMessage(Message.raw("Colony '" + colony.getName() + "' now has " +
                        colony.getWarehouseCount() + " warehouse(s)."));
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
            super("list", "List warehouses in a colony");
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

            List<WarehouseData> warehouses = colony.getWarehouses();

            if (warehouses.isEmpty()) {
                ctx.sendMessage(Message.raw("Colony '" + colony.getName() + "' has no warehouses."));
                ctx.sendMessage(Message.raw("Use /warehouse register [colony] to add one."));
                return;
            }

            ctx.sendMessage(Message.raw("=== Warehouses in " + colony.getName() + " ==="));
            ctx.sendMessage(Message.raw("Total: " + warehouses.size()));

            int index = 1;
            for (WarehouseData warehouse : warehouses) {
                Vector3i pos = warehouse.getPosition();
                ctx.sendMessage(Message.raw("  " + index + ". " + warehouse.getBlockType() +
                        " at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"));
                index++;
            }
        }
    }

    // =====================
    // Subcommand: unregister
    // =====================
    private static class UnregisterSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final WarehouseRegistry warehouseRegistry;
        private final InventoryCacheService inventoryCache;
        private final InventoryChangeHandler changeHandler;
        private final RequiredArg<String> colonyIdArg;

        public UnregisterSubCommand(ColonyService colonyService, WarehouseRegistry warehouseRegistry,
                                    InventoryCacheService inventoryCache, InventoryChangeHandler changeHandler) {
            super("unregister", "Remove a warehouse from a colony");
            this.colonyService = colonyService;
            this.warehouseRegistry = warehouseRegistry;
            this.inventoryCache = inventoryCache;
            this.changeHandler = changeHandler;
            this.colonyIdArg = withRequiredArg("colony", "Colony name or UUID", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);

            ColonyData colony = resolveColonyWithFeedback(colonyService, ctx, colonyIdentifier);
            if (colony == null) {
                return;
            }

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

            UUID colonyId = colony.getColonyId();

            // Execute on world thread to access store
            world.execute(() -> {
                Store<EntityStore> store = playerRef.getStore();
                Vector3i targetPos = getTargetBlock(store, playerRef);

                if (targetPos == null) {
                    ctx.sendMessage(Message.raw("Could not determine target block position."));
                    return;
                }

                // Check if warehouse exists
                if (!colony.hasWarehouseAt(targetPos)) {
                    ctx.sendMessage(Message.raw("No warehouse registered at this position."));
                    return;
                }

                // Unregister the event listener first
                changeHandler.unregisterContainerListener(targetPos);

                // Unregister the warehouse
                colony.removeWarehouse(targetPos);
                warehouseRegistry.unregisterWarehouse(targetPos);
                inventoryCache.unregisterWarehouse(colonyId, targetPos);

                ctx.sendMessage(Message.raw("Unregistered warehouse at (" +
                        targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + ")"));
                ctx.sendMessage(Message.raw("Colony '" + colony.getName() + "' now has " +
                        colony.getWarehouseCount() + " warehouse(s)."));
            });
        }
    }

    // =====================
    // Subcommand: stock (test command)
    // =====================
    private static class StockSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final InventoryCacheService inventoryCache;
        private final RequiredArg<String> colonyIdArg;
        private final RequiredArg<String> itemIdArg;
        private final RequiredArg<String> quantityArg;

        public StockSubCommand(ColonyService colonyService, InventoryCacheService inventoryCache) {
            super("stock", "Add test items to a warehouse (debug command)");
            this.colonyService = colonyService;
            this.inventoryCache = inventoryCache;
            this.colonyIdArg = withRequiredArg("colony", "Colony name or UUID", ArgTypes.STRING);
            this.itemIdArg = withRequiredArg("item", "Item ID to add", ArgTypes.STRING);
            this.quantityArg = withRequiredArg("quantity", "Quantity to add", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
            String colonyIdentifier = ctx.get(colonyIdArg);
            String itemId = ctx.get(itemIdArg);
            String quantityStr = ctx.get(quantityArg);

            int quantity;
            try {
                quantity = Integer.parseInt(quantityStr);
                if (quantity <= 0) {
                    ctx.sendMessage(Message.raw("Quantity must be a positive number."));
                    return;
                }
            } catch (NumberFormatException e) {
                ctx.sendMessage(Message.raw("Invalid quantity: " + quantityStr));
                return;
            }

            ColonyData colony = resolveColonyWithFeedback(colonyService, ctx, colonyIdentifier);
            if (colony == null) {
                return;
            }

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

            UUID colonyId = colony.getColonyId();

            // Execute on world thread to access store
            world.execute(() -> {
                Store<EntityStore> store = playerRef.getStore();
                Vector3i targetPos = getTargetBlock(store, playerRef);

                if (targetPos == null) {
                    ctx.sendMessage(Message.raw("Could not determine target block position."));
                    return;
                }

                // Check if warehouse exists
                if (!colony.hasWarehouseAt(targetPos)) {
                    ctx.sendMessage(Message.raw("No warehouse registered at this position."));
                    ctx.sendMessage(Message.raw("Use /warehouse register first."));
                    return;
                }

                // Get current contents and add the new item
                List<ItemEntry> currentContents = new ArrayList<>(
                        inventoryCache.getWarehouseContents(colonyId, targetPos)
                );

                // Check if item already exists and update quantity
                boolean found = false;
                for (int i = 0; i < currentContents.size(); i++) {
                    ItemEntry entry = currentContents.get(i);
                    if (entry.itemId().equals(itemId)) {
                        currentContents.set(i, new ItemEntry(itemId, entry.quantity() + quantity));
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    currentContents.add(new ItemEntry(itemId, quantity));
                }

                // Update the cache
                inventoryCache.invalidateAndUpdate(colonyId, targetPos, currentContents);

                ctx.sendMessage(Message.raw("Added " + quantity + "x " + itemId + " to warehouse at (" +
                        targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + ")"));

                // Show current contents
                ctx.sendMessage(Message.raw("Warehouse contents:"));
                for (ItemEntry entry : currentContents) {
                    ctx.sendMessage(Message.raw("  - " + entry.itemId() + " x" + entry.quantity()));
                }
            });
        }
    }

    // =====================
    // Subcommand: toggle
    // =====================
    private static class ToggleSubCommand extends CommandBase {
        private final ColonyService colonyService;
        private final WarehouseRegistry warehouseRegistry;
        private final InventoryCacheService inventoryCache;
        private final InventoryChangeHandler changeHandler;

        public ToggleSubCommand(ColonyService colonyService, WarehouseRegistry warehouseRegistry,
                                InventoryCacheService inventoryCache, InventoryChangeHandler changeHandler) {
            super("toggle", "Toggle warehouse registration for looked-at container");
            this.colonyService = colonyService;
            this.warehouseRegistry = warehouseRegistry;
            this.inventoryCache = inventoryCache;
            this.changeHandler = changeHandler;
        }

        @Override
        protected void executeSync(@Nonnull CommandContext ctx) {
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

            // Execute on world thread to access store
            world.execute(() -> {
                Store<EntityStore> store = playerRef.getStore();
                Vector3i targetPos = getTargetBlock(store, playerRef);

                if (targetPos == null) {
                    ctx.sendMessage(Message.raw("Could not determine target block position."));
                    return;
                }

                // 1. Verify it's a container
                long chunkKey = ChunkUtil.indexChunkFromBlock(targetPos.getX(), targetPos.getZ());
                WorldChunk chunk = world.getChunkIfLoaded(chunkKey);

                if (chunk == null) {
                    ctx.sendMessage(Message.raw("Chunk not loaded at target position."));
                    return;
                }

                int localX = ChunkUtil.localCoordinate(targetPos.getX());
                int localY = targetPos.getY();
                int localZ = ChunkUtil.localCoordinate(targetPos.getZ());

                var blockState = chunk.getState(localX, localY, localZ);

                if (!(blockState instanceof ItemContainerBlockState containerState) || containerState.getItemContainer() == null) {
                    ctx.sendMessage(Message.raw("Block at target position is not a valid container."));
                    return;
                }

                // 2. Find colony context
                String worldId = world.getName() != null ? world.getName() : "default";
                ColonyData colony = findColonyContainingPosition(colonyService, targetPos, worldId);

                if (colony == null) {
                    ctx.sendMessage(Message.raw("No colony found at this location."));
                    return;
                }

                UUID colonyId = colony.getColonyId();

                try {
                    if (colony.hasWarehouseAt(targetPos)) {
                        // Unregister
                        changeHandler.unregisterContainerListener(targetPos);
                        colony.removeWarehouse(targetPos);
                        warehouseRegistry.unregisterWarehouse(targetPos);
                        inventoryCache.unregisterWarehouse(colonyId, targetPos);

                        ctx.sendMessage(Message.raw("Unregistered warehouse at (" +
                                targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + ")"));
                    } else {
                        // Register
                        int capacity = containerState.getItemContainer().getCapacity();
                        WarehouseData warehouseData = new WarehouseData(
                                targetPos,
                                "Container",
                                capacity,
                                worldId
                        );
                        colony.addWarehouse(warehouseData);
                        warehouseRegistry.registerWarehouse(colonyId, targetPos);
                        inventoryCache.registerWarehouse(colonyId, targetPos);
                        changeHandler.registerContainerListener(colonyId, targetPos, world);

                        ctx.sendMessage(Message.raw("Registered warehouse at (" +
                                targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + ")"));
                        ctx.sendMessage(Message.raw("Colony '" + colony.getName() + "' now has " +
                                colony.getWarehouseCount() + " warehouse(s)."));
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().withCause(e).log("Failed to toggle warehouse registration");
                    ctx.sendMessage(Message.raw("Failed to toggle warehouse registration."));
                }
            });
        }
    }
}