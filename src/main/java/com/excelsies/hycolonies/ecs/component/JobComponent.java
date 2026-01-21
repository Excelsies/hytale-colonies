package com.excelsies.hycolonies.ecs.component;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/**
 * ECS Component that stores job-related data for colony citizens.
 * Tracks the citizen's profession, current task, state machine state, and experience.
 */
public class JobComponent implements Component<EntityStore> {

    private static ComponentType<EntityStore, JobComponent> COMPONENT_TYPE;

    public static final BuilderCodec<JobComponent> CODEC = BuilderCodec.builder(JobComponent.class, JobComponent::new)
            .append(new KeyedCodec<>("JobType", Codec.STRING),
                    (c, v, i) -> c.jobType = JobType.fromString(v),
                    (c, i) -> c.jobType.name())
            .add()
            .append(new KeyedCodec<>("CurrentState", Codec.STRING),
                    (c, v, i) -> c.currentState = v != null ? v : "IDLE",
                    (c, i) -> c.currentState)
            .add()
            .append(new KeyedCodec<>("AssignedTaskId", Codec.STRING),
                    (c, v, i) -> c.assignedTaskId = v != null && !v.isEmpty() ? UUID.fromString(v) : null,
                    (c, i) -> c.assignedTaskId != null ? c.assignedTaskId.toString() : "")
            .add()
            .append(new KeyedCodec<>("ExperiencePoints", Codec.STRING),
                    (c, v, i) -> c.experiencePoints = v != null && !v.isEmpty() ? Integer.parseInt(v) : 0,
                    (c, i) -> String.valueOf(c.experiencePoints))
            .add()
            .append(new KeyedCodec<>("StateStartTime", Codec.STRING),
                    (c, v, i) -> c.stateStartTime = v != null && !v.isEmpty() ? Long.parseLong(v) : System.currentTimeMillis(),
                    (c, i) -> String.valueOf(c.stateStartTime))
            .add()
            .build();

    private JobType jobType;
    private String currentState;
    private UUID assignedTaskId;
    private int experiencePoints;
    private long stateStartTime;

    /**
     * Default constructor for deserialization.
     */
    public JobComponent() {
        this.jobType = JobType.UNEMPLOYED;
        this.currentState = "IDLE";
        this.assignedTaskId = null;
        this.experiencePoints = 0;
        this.stateStartTime = System.currentTimeMillis();
    }

    /**
     * Constructor with job type.
     */
    public JobComponent(JobType jobType) {
        this.jobType = jobType;
        this.currentState = "IDLE";
        this.assignedTaskId = null;
        this.experiencePoints = 0;
        this.stateStartTime = System.currentTimeMillis();
    }

    /**
     * Full constructor.
     */
    public JobComponent(JobType jobType, String currentState, UUID assignedTaskId, int experiencePoints) {
        this.jobType = jobType;
        this.currentState = currentState != null ? currentState : "IDLE";
        this.assignedTaskId = assignedTaskId;
        this.experiencePoints = experiencePoints;
        this.stateStartTime = System.currentTimeMillis();
    }

    // Getters
    public JobType getJobType() {
        return jobType;
    }

    public String getCurrentState() {
        return currentState;
    }

    public UUID getAssignedTaskId() {
        return assignedTaskId;
    }

    public int getExperiencePoints() {
        return experiencePoints;
    }

    public long getStateStartTime() {
        return stateStartTime;
    }

    // Setters
    public void setJobType(JobType jobType) {
        this.jobType = jobType;
    }

    public void setCurrentState(String state) {
        this.currentState = state;
        this.stateStartTime = System.currentTimeMillis();
    }

    public void setAssignedTaskId(UUID taskId) {
        this.assignedTaskId = taskId;
    }

    public void addExperience(int amount) {
        this.experiencePoints += amount;
    }

    /**
     * Returns the time in milliseconds since the current state started.
     */
    public long getTimeInCurrentState() {
        return System.currentTimeMillis() - stateStartTime;
    }

    /**
     * Returns true if the citizen has no assigned task.
     */
    public boolean isIdle() {
        return assignedTaskId == null && "IDLE".equals(currentState);
    }

    /**
     * Returns true if the citizen is a courier.
     */
    public boolean isCourier() {
        return jobType == JobType.COURIER;
    }

    /**
     * Clears the current task and resets to idle state.
     */
    public void clearTask() {
        this.assignedTaskId = null;
        this.currentState = "IDLE";
        this.stateStartTime = System.currentTimeMillis();
    }

    // Component type management
    public static ComponentType<EntityStore, JobComponent> getComponentType() {
        return COMPONENT_TYPE;
    }

    public static void setComponentType(ComponentType<EntityStore, JobComponent> type) {
        COMPONENT_TYPE = type;
    }

    @Override
    public Component<EntityStore> clone() {
        JobComponent clone = new JobComponent(jobType, currentState, assignedTaskId, experiencePoints);
        clone.stateStartTime = this.stateStartTime;
        return clone;
    }
}
