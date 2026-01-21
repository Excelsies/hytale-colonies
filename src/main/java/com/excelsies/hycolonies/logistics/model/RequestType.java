package com.excelsies.hycolonies.logistics.model;

/**
 * Types of item requests in the logistics system.
 */
public enum RequestType {
    /**
     * Citizen needs food to survive.
     */
    HUNGER,

    /**
     * Builder needs materials for construction.
     */
    BUILDING,

    /**
     * Crafting station or workbench needs input materials.
     */
    WORK_ORDER,

    /**
     * Items need to be moved for storage organization.
     */
    STORAGE,

    /**
     * Manual request from player command.
     */
    MANUAL
}
