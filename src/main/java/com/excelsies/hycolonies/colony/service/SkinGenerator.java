package com.excelsies.hycolonies.colony.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Utility class to generate random cosmetic configurations for Avatar citizens.
 * Dynamically loads valid cosmetics and colors from game assets.
 */
public class SkinGenerator {

    private static final Random RANDOM = new Random();
    private static final Gson GSON = new Gson();

    // Map Keys
    public static final String KEY_BODY = "BODY";
    public static final String KEY_UNDERWEAR = "UNDERWEAR";
    public static final String KEY_FACE = "FACE";
    public static final String KEY_MOUTH = "MOUTH";
    public static final String KEY_EARS = "EARS";
    public static final String KEY_HAIR = "HAIR";
    public static final String KEY_EYES = "EYES";
    public static final String KEY_EYEBROWS = "EYEBROWS";
    public static final String KEY_PANTS = "PANTS";
    public static final String KEY_SHOES = "SHOES";
    public static final String KEY_OVERTOP = "OVERTOP";
    public static final String KEY_UNDERTOP = "UNDERTOP";
    public static final String KEY_OVERPANTS = "OVERPANTS";
    public static final String KEY_CAPE = "CAPE";
    public static final String KEY_HEAD_ACCESSORY = "HEAD_ACCESSORY";
    public static final String KEY_FACE_ACCESSORY = "FACE_ACCESSORY";
    public static final String KEY_EAR_ACCESSORY = "EAR_ACCESSORY";
    public static final String KEY_FACIAL_HAIR = "FACIAL_HAIR";
    public static final String KEY_GLOVES = "GLOVES";

    // Defaults
    public static final String DEFAULT_SKIN_TONE = "04";
    public static final String DEFAULT_BODY = "Default." + DEFAULT_SKIN_TONE;
    public static final String DEFAULT_UNDERWEAR = "Boxer.White";
    public static final String DEFAULT_FACE = "Face_Neutral";
    public static final String DEFAULT_EYES = "Medium_Eyes.Blue";
    public static final String DEFAULT_EYEBROWS = "Medium.Brown";
    public static final String DEFAULT_MOUTH = "Mouth_Default";
    public static final String DEFAULT_EARS = "Default";
    public static final String DEFAULT_HAIRCUT = "Morning.Brown";
    public static final String DEFAULT_PANTS = "Jeans.Blue";
    public static final String DEFAULT_OVERTOP = "Tartan.Red";
    public static final String DEFAULT_SHOES = "BasicBoots.Brown";

    // Map of GradientSetID -> List of ColorIDs
    private static final Map<String, List<String>> GRADIENT_SETS = new HashMap<>();
    
    // Map of ItemID -> GradientSetID
    private static final Map<String, String> ITEM_GRADIENT_MAP = new HashMap<>();

    // Lists of Item IDs per category
    private static final List<String> FACES = new ArrayList<>();
    private static final List<String> HAIRCUTS = new ArrayList<>();
    private static final List<String> OVERTOPS = new ArrayList<>();
    private static final List<String> PANTS = new ArrayList<>();
    private static final List<String> SHOES = new ArrayList<>();
    private static final List<String> UNDERTOPS = new ArrayList<>();
    private static final List<String> OVERPANTS = new ArrayList<>();
    private static final List<String> CAPES = new ArrayList<>();
    private static final List<String> HEAD_ACCESSORY = new ArrayList<>();
    private static final List<String> FACE_ACCESSORY = new ArrayList<>();
    private static final List<String> EAR_ACCESSORY = new ArrayList<>();
    private static final List<String> FACIAL_HAIR = new ArrayList<>();
    private static final List<String> GLOVES = new ArrayList<>();
    private static final List<String> EYES = new ArrayList<>();
    private static final List<String> EYEBROWS = new ArrayList<>();
    private static final List<String> MOUTHS = new ArrayList<>();
    private static final List<String> EARS = new ArrayList<>();
    private static final List<String> BODY_CHARACTERISTICS = new ArrayList<>();
    private static final List<String> UNDERWEAR = new ArrayList<>();

    static {
        loadData();
    }

    private static void loadData() {
        // 1. Load Gradient Sets
        loadGradientSets("cosmetics/GradientSets.json");

        // 2. Load Items
        loadCategory("cosmetics/Faces.json", FACES);
        loadCategory("cosmetics/Haircuts.json", HAIRCUTS);
        loadCategory("cosmetics/Overtops.json", OVERTOPS);
        loadCategory("cosmetics/Pants.json", PANTS);
        loadCategory("cosmetics/Shoes.json", SHOES);
        loadCategory("cosmetics/Undertops.json", UNDERTOPS);
        loadCategory("cosmetics/Overpants.json", OVERPANTS);
        loadCategory("cosmetics/Capes.json", CAPES);
        loadCategory("cosmetics/HeadAccessory.json", HEAD_ACCESSORY);
        loadCategory("cosmetics/FaceAccessory.json", FACE_ACCESSORY);
        loadCategory("cosmetics/EarAccessory.json", EAR_ACCESSORY);
        loadCategory("cosmetics/FacialHair.json", FACIAL_HAIR);
        loadCategory("cosmetics/Gloves.json", GLOVES);
        loadCategory("cosmetics/Eyes.json", EYES);
        loadCategory("cosmetics/Eyebrows.json", EYEBROWS);
        loadCategory("cosmetics/Mouths.json", MOUTHS);
        loadCategory("cosmetics/Ears.json", EARS);
        loadCategory("cosmetics/BodyCharacteristics.json", BODY_CHARACTERISTICS);
        loadCategory("cosmetics/Underwear.json", UNDERWEAR);
    }

    private static void loadGradientSets(String path) {
        try (Reader reader = getReader(path)) {
            if (reader == null) return;
            Type listType = new TypeToken<List<GradientSetDef>>(){}.getType();
            List<GradientSetDef> sets = GSON.fromJson(reader, listType);
            for (GradientSetDef set : sets) {
                if (set.Gradients != null) {
                    GRADIENT_SETS.put(set.Id, new ArrayList<>(set.Gradients.keySet()));
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load GradientSets: " + e.getMessage());
        }
        
        // Register Virtual Gradients after loading (to ensure no overwrites, though they are unique keys)
        registerVirtualGradients();
    }

    private static void registerVirtualGradients() {
        GRADIENT_SETS.put("TEXTURE_RED", Arrays.asList("Red"));
        GRADIENT_SETS.put("TEXTURE_BLUE", Arrays.asList("Blue"));
        GRADIENT_SETS.put("TEXTURE_GREEN", Arrays.asList("Green"));
        GRADIENT_SETS.put("TEXTURE_BLACK", Arrays.asList("Black"));
        GRADIENT_SETS.put("TEXTURE_WHITE", Arrays.asList("White"));
        GRADIENT_SETS.put("TEXTURE_BROWN", Arrays.asList("Brown"));
        GRADIENT_SETS.put("TEXTURE_MOSSY", Arrays.asList("Mossy"));
        GRADIENT_SETS.put("TEXTURE_ACORN", Arrays.asList("Acorn"));
        GRADIENT_SETS.put("TEXTURE_DEFAULT", Arrays.asList("Default"));
        GRADIENT_SETS.put("TEXTURE_SILVER", Arrays.asList("Silver"));
        GRADIENT_SETS.put("TEXTURE_STRAWBERRY", Arrays.asList("Strawberry"));
        GRADIENT_SETS.put("TEXTURE_ICECREAM", Arrays.asList("Chocolate", "Strawberry"));
    }

    private static void loadCategory(String path, List<String> targetList) {
        try (Reader reader = getReader(path)) {
            if (reader == null) return;
            Type listType = new TypeToken<List<CosmeticItemDef>>(){}.getType();
            List<CosmeticItemDef> items = GSON.fromJson(reader, listType);
            
            for (CosmeticItemDef item : items) {
                if (item.Id == null) continue;
                
                targetList.add(item.Id);
                
                if (item.GradientSet != null) {
                    ITEM_GRADIENT_MAP.put(item.Id, item.GradientSet);
                } else if (item.Textures != null && !item.Textures.isEmpty()) {
                    String virtualSetId = "TEXTURE_SET_" + item.Id;
                    GRADIENT_SETS.put(virtualSetId, new ArrayList<>(item.Textures.keySet()));
                    ITEM_GRADIENT_MAP.put(item.Id, virtualSetId);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load category " + path + ": " + e.getMessage());
        }
        
        // Register Manual Mappings
        registerManualMappings();
    }
    
    private static void registerManualMappings() {
        // Manual texture mappings for items that might not have been caught or need specific overrides
        ITEM_GRADIENT_MAP.put("Head_Crown", "TEXTURE_RED");
        ITEM_GRADIENT_MAP.put("TopHat", "TEXTURE_RED");
        ITEM_GRADIENT_MAP.put("CowboyHat", "TEXTURE_RED");
        ITEM_GRADIENT_MAP.put("AcornEarrings", "TEXTURE_ACORN");
        ITEM_GRADIENT_MAP.put("AcornNecktie", "TEXTURE_ACORN");
        ITEM_GRADIENT_MAP.put("AcornHairclip", "TEXTURE_ACORN");
        ITEM_GRADIENT_MAP.put("MouthWheat", "TEXTURE_GREEN");
        ITEM_GRADIENT_MAP.put("Hope_Of_Gaia_Skirt", "TEXTURE_GREEN");
        ITEM_GRADIENT_MAP.put("Shorty_Mossy", "TEXTURE_GREEN");
        ITEM_GRADIENT_MAP.put("Forest_Guardian_Hat", "TEXTURE_MOSSY");
        ITEM_GRADIENT_MAP.put("Forest_Guardian_Poncho", "TEXTURE_MOSSY");
        ITEM_GRADIENT_MAP.put("Icecream_Shoes", "TEXTURE_STRAWBERRY");
        ITEM_GRADIENT_MAP.put("Icecream_Skirt", "TEXTURE_ICECREAM");
        ITEM_GRADIENT_MAP.put("Head_Tiara", "TEXTURE_SILVER");
        ITEM_GRADIENT_MAP.put("SilverHoopsBead", "TEXTURE_DEFAULT");
    }
    
    private static Reader getReader(String path) {
        return new InputStreamReader(Objects.requireNonNull(SkinGenerator.class.getClassLoader().getResourceAsStream(path)));
    }

    /**
     * Generates a random cosmetic configuration as a JSON string.
     */
    public static String generateRandomSkin() {
        Map<String, String> cosmetics = new HashMap<>();

        // Pick consistent Skin Tone from "Skin" gradient set
        String skinTone = pickColorSafe("Skin", DEFAULT_SKIN_TONE);

        // Mandatory - No Color (Strings)
        cosmetics.put(KEY_FACE, getRandom(FACES));
        cosmetics.put(KEY_MOUTH, getRandom(MOUTHS));
        cosmetics.put(KEY_EARS, getRandom(EARS));

        // Mandatory - Needs Color
        String bodyId = getRandom(BODY_CHARACTERISTICS);
        if (bodyId == null) bodyId = "Default";
        cosmetics.put(KEY_BODY, bodyId + "." + skinTone);
        
        // Boxer uses Colored_Cotton usually
        cosmetics.put(KEY_UNDERWEAR, "Boxer." + pickColorSafe("Colored_Cotton", "White"));

        // Hair items
        cosmetics.put(KEY_HAIR, generateItemWithColor(HAIRCUTS));
        cosmetics.put(KEY_EYEBROWS, generateItemWithColor(EYEBROWS));
        // Eyes
        cosmetics.put(KEY_EYES, generateItemWithColor(EYES));

        // Clothing
        cosmetics.put(KEY_PANTS, generateItemWithColor(PANTS));
        cosmetics.put(KEY_SHOES, generateItemWithColor(SHOES));

        // Optional
        if (RANDOM.nextBoolean()) cosmetics.put(KEY_OVERTOP, generateItemWithColor(OVERTOPS));
        if (RANDOM.nextDouble() < 0.9) cosmetics.put(KEY_UNDERTOP, generateItemWithColor(UNDERTOPS));
        if (RANDOM.nextDouble() < 0.5) cosmetics.put(KEY_OVERPANTS, generateItemWithColor(OVERPANTS));
        if (RANDOM.nextDouble() < 0.2) cosmetics.put(KEY_CAPE, generateItemWithColor(CAPES));
        if (RANDOM.nextDouble() < 0.3) cosmetics.put(KEY_HEAD_ACCESSORY, generateItemWithColor(HEAD_ACCESSORY));
        if (RANDOM.nextDouble() < 0.2) cosmetics.put(KEY_FACE_ACCESSORY, generateItemWithColor(FACE_ACCESSORY));
        if (RANDOM.nextDouble() < 0.2) cosmetics.put(KEY_EAR_ACCESSORY, generateItemWithColor(EAR_ACCESSORY));
        if (RANDOM.nextDouble() < 0.3) cosmetics.put(KEY_FACIAL_HAIR, generateItemWithColor(FACIAL_HAIR));
        if (RANDOM.nextDouble() < 0.3) cosmetics.put(KEY_GLOVES, generateItemWithColor(GLOVES));

        return GSON.toJson(cosmetics);
    }

    private static String generateItemWithColor(List<String> items) {
        String item = getRandom(items);
        if (item == null) return null;
        
        String gradientSet = ITEM_GRADIENT_MAP.get(item);
        String color;
        
        if (gradientSet != null) {
            color = pickColorSafe(gradientSet, "White");
        } else {
            // Fallback
            color = pickColorSafe("Colored_Cotton", "White");
        }
        
        return item + "." + color;
    }

    private static String pickColorSafe(String gradientSetId, String fallback) {
        List<String> colors = GRADIENT_SETS.get(gradientSetId);
        if (colors == null || colors.isEmpty()) return fallback;
        return colors.get(RANDOM.nextInt(colors.size()));
    }

    private static String getRandom(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        return list.get(RANDOM.nextInt(list.size()));
    }

    private static class GradientSetDef {
        String Id;
        Map<String, Object> Gradients;
    }
    
    private static class CosmeticItemDef {
        String Id;
        String GradientSet;
        Map<String, Object> Textures;
    }
}