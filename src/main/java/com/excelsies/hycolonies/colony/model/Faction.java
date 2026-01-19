package com.excelsies.hycolonies.colony.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Represents the faction of a colony.
 * Determines which NPC types can be spawned as citizens.
 */
public enum Faction {
    KWEEBEC("Kweebec",
            "Kweebec_Elder", "Kweebec_Merchant", "Kweebec_Razorleaf", "Kweebec_Rootling",
            "Kweebec_Sapling_Orange", "Kweebec_Sapling_Pink", "Kweebec_Seedling", "Kweebec_Sproutling"
    ),
    FERAN(
            "Feran",
            "Feran_Burrower", "Feran_Civilian", "Feran_Cub", "Feran_Longtooth", "Feran_Sharptooth", "Feran_Windwalker"
    ),
    KLOPS(
            "Klops",
            "Klops_Gentleman", "Klops_Merchant", "Klops_Miner"
    ),
    SLOTHIAN(
            "Slothian",
            "Slothian_Elder", "Slothian_Kid", "Slothian_Monk", "Slothian_Scout", "Slothian_Villager", "Slothian_Warrior"
    ),
    OUTLANDER(
            "Outlander",
            "Outlander_Citizen_Berserker", "Outlander_Citizen_Cultist", "Outlander_Citizen_Hunter", "Outlander_Citizen_Marauder",
            "Outlander_Citizen_Peon", "Outlander_Citizen_Priest", "Outlander_Citizen_Sorcerer", "Outlander_Citizen_Stalker"
    ),
    TRORK(
            "Trork",
            "Trork_Brawler", "Trork_Chieftain", "Trork_Doctor_Witch", "Trork_Guard", "Trork_Hunter",
            "Trork_Mauler", "Trork_Sentry", "Trork_Shaman", "Trork_Unarmed", "Trork_Warrior"
    ),
    GOBLIN(
            "Goblin",
            "Goblin_Hermit", "Goblin_Lobber", "Goblin_Miner",
            "Goblin_Scavenger", "Goblin_Scrapper", "Goblin_Thief"
    );

    private final String displayName;
    private final List<String> npcSkins;
    private static final Random RANDOM = new Random();

    Faction(String displayName, String... npcSkins) {
        this.displayName = displayName;
        this.npcSkins = Collections.unmodifiableList(Arrays.asList(npcSkins));
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getNpcSkins() {
        return npcSkins;
    }

    /**
     * Gets a random NPC skin from this faction.
     * @return A random skin name, or null if the list is empty.
     */
    public String getRandomSkin() {
        if (npcSkins.isEmpty()) {
            return null;
        }
        return npcSkins.get(RANDOM.nextInt(npcSkins.size()));
    }

    /**
     * Tries to find a Faction by name (case-insensitive).
     * @param name The name to search for.
     * @return The Faction, or null if not found.
     */
    public static Faction fromString(String name) {
        for (Faction f : values()) {
            if (f.name().equalsIgnoreCase(name) || f.displayName.equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }
}
