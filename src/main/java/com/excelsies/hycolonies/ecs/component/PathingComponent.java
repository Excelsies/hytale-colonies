package com.excelsies.hycolonies.ecs.component;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * ECS Component that stores pathfinding state for NPC navigation.
 * Tracks the target position, current waypoint, and pathing status.
 */
public class PathingComponent implements Component<EntityStore> {

    private static ComponentType<EntityStore, PathingComponent> COMPONENT_TYPE;

    public static final BuilderCodec<PathingComponent> CODEC = BuilderCodec.builder(PathingComponent.class, PathingComponent::new)
            .append(new KeyedCodec<>("TargetX", Codec.STRING),
                    (c, v, i) -> c.targetX = v != null && !v.isEmpty() ? Double.parseDouble(v) : 0.0,
                    (c, i) -> String.valueOf(c.targetX))
            .add()
            .append(new KeyedCodec<>("TargetY", Codec.STRING),
                    (c, v, i) -> c.targetY = v != null && !v.isEmpty() ? Double.parseDouble(v) : 0.0,
                    (c, i) -> String.valueOf(c.targetY))
            .add()
            .append(new KeyedCodec<>("TargetZ", Codec.STRING),
                    (c, v, i) -> c.targetZ = v != null && !v.isEmpty() ? Double.parseDouble(v) : 0.0,
                    (c, i) -> String.valueOf(c.targetZ))
            .add()
            .append(new KeyedCodec<>("Status", Codec.STRING),
                    (c, v, i) -> c.status = PathingStatus.fromString(v),
                    (c, i) -> c.status.name())
            .add()
            .append(new KeyedCodec<>("RetryCount", Codec.STRING),
                    (c, v, i) -> c.retryCount = v != null && !v.isEmpty() ? Integer.parseInt(v) : 0,
                    (c, i) -> String.valueOf(c.retryCount))
            .add()
            .append(new KeyedCodec<>("LastMoveTime", Codec.STRING),
                    (c, v, i) -> c.lastMoveTime = v != null && !v.isEmpty() ? Long.parseLong(v) : System.currentTimeMillis(),
                    (c, i) -> String.valueOf(c.lastMoveTime))
            .add()
            .build();

    private double targetX;
    private double targetY;
    private double targetZ;
    private PathingStatus status;
    private int retryCount;
    private long lastMoveTime;

    // Runtime fields (not persisted)
    private transient Vector3d cachedTarget;

    /**
     * Default constructor for deserialization.
     */
    public PathingComponent() {
        this.targetX = 0;
        this.targetY = 0;
        this.targetZ = 0;
        this.status = PathingStatus.IDLE;
        this.retryCount = 0;
        this.lastMoveTime = System.currentTimeMillis();
    }

    /**
     * Constructor with target position.
     */
    public PathingComponent(Vector3d target) {
        this.targetX = target.getX();
        this.targetY = target.getY();
        this.targetZ = target.getZ();
        this.status = PathingStatus.COMPUTING;
        this.retryCount = 0;
        this.lastMoveTime = System.currentTimeMillis();
        this.cachedTarget = target;
    }

    // Getters
    public Vector3d getTarget() {
        if (cachedTarget == null) {
            cachedTarget = new Vector3d(targetX, targetY, targetZ);
        }
        return cachedTarget;
    }

    public PathingStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public long getLastMoveTime() {
        return lastMoveTime;
    }

    // Setters
    public void setTarget(Vector3d target) {
        this.targetX = target.getX();
        this.targetY = target.getY();
        this.targetZ = target.getZ();
        this.cachedTarget = target;
        this.status = PathingStatus.COMPUTING;
        this.retryCount = 0;
    }

    public void setStatus(PathingStatus status) {
        this.status = status;
        if (status == PathingStatus.MOVING) {
            this.lastMoveTime = System.currentTimeMillis();
        }
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }

    public void resetRetryCount() {
        this.retryCount = 0;
    }

    public void updateMoveTime() {
        this.lastMoveTime = System.currentTimeMillis();
    }

    /**
     * Returns true if the NPC has been stuck (no movement) for too long.
     * @param maxStuckTimeMs Maximum time in milliseconds before considered stuck
     */
    public boolean isStuck(long maxStuckTimeMs) {
        return status == PathingStatus.MOVING &&
               (System.currentTimeMillis() - lastMoveTime) > maxStuckTimeMs;
    }

    /**
     * Returns true if the NPC has exceeded the retry limit.
     * @param maxRetries Maximum number of path computation retries
     */
    public boolean hasExceededRetries(int maxRetries) {
        return retryCount >= maxRetries;
    }

    /**
     * Clears the path and resets to idle state.
     */
    public void clearPath() {
        this.status = PathingStatus.IDLE;
        this.retryCount = 0;
    }

    /**
     * Calculates squared distance from a position to the target.
     */
    public double distanceSquaredToTarget(Vector3d from) {
        double dx = targetX - from.getX();
        double dy = targetY - from.getY();
        double dz = targetZ - from.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Returns true if the position is within the arrival threshold of the target.
     */
    public boolean hasArrived(Vector3d currentPos, double threshold) {
        return distanceSquaredToTarget(currentPos) <= threshold * threshold;
    }

    // Component type management
    public static ComponentType<EntityStore, PathingComponent> getComponentType() {
        return COMPONENT_TYPE;
    }

    public static void setComponentType(ComponentType<EntityStore, PathingComponent> type) {
        COMPONENT_TYPE = type;
    }

    @Override
    public Component<EntityStore> clone() {
        PathingComponent clone = new PathingComponent();
        clone.targetX = this.targetX;
        clone.targetY = this.targetY;
        clone.targetZ = this.targetZ;
        clone.status = this.status;
        clone.retryCount = this.retryCount;
        clone.lastMoveTime = this.lastMoveTime;
        return clone;
    }
}
