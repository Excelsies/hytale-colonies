package com.excelsies.hycolonies.ui;

import com.excelsies.hycolonies.colony.model.ColonyData;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * HUD overlay displaying warehouse registration status when a player opens
 * a container inside a colony. Supports both small (1-2 row) and large (3+ row)
 * container layouts.
 */
public class ColonyContainerHud extends CustomUIHud {

    private static final int COLUMNS = 9;
    private static final int BASE_ROWS = 2;

    private final ColonyData colony;
    private final Vector3i blockPos;
    private final int containerCapacity;

    public ColonyContainerHud(PlayerRef playerRef, ColonyData colony, Vector3i blockPos, int containerCapacity) {
        super(playerRef);
        this.colony = colony;
        this.blockPos = blockPos;
        this.containerCapacity = containerCapacity;
    }

    @Override
    protected void build(UICommandBuilder commandBuilder) {
        commandBuilder.append("Hud/ColonyContainerHud.ui");

        // Determine which layout to show based on container row count
        int rows = (containerCapacity + COLUMNS - 1) / COLUMNS;
        boolean isLarge = rows > BASE_ROWS;

        if (isLarge) {
            commandBuilder.set("#SmallLayout.Visible", false);
            commandBuilder.set("#LargeLayout.Visible", true);
        }

        // Set status text on the visible panel
        boolean isRegistered = colony.hasWarehouseAt(blockPos);
        String statusText = isRegistered ? "Registered to " + colony.getName() : "Not registered";
        commandBuilder.set("#SmallStatusLabel.Text", statusText);
        commandBuilder.set("#LargeStatusLabel.Text", statusText);
    }

    /**
     * Hides the HUD by setting both layouts to invisible.
     * Call this when the container is closed.
     */
    public void hide() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        commandBuilder.set("#SmallLayout.Visible", false);
        commandBuilder.set("#LargeLayout.Visible", false);
        update(false, commandBuilder);
    }
}
