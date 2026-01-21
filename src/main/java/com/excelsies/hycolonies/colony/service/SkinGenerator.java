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
    }

    private static void loadCategory(String path, List<String> targetList) {
        try (Reader reader = getReader(path)) {
            if (reader == null) return;
            Type listType = new TypeToken<List<CosmeticItemDef>>(){}.getType();
            List<CosmeticItemDef> items = GSON.fromJson(reader, listType);
            
            for (CosmeticItemDef item : items) {
                if (item.Id == null) continue;
                
                targetList.add(item.Id);
                
                // Determine Gradient/Color Source
                if (item.GradientSet != null) {
                    ITEM_GRADIENT_MAP.put(item.Id, item.GradientSet);
                } else if (item.Textures != null && !item.Textures.isEmpty()) {
                    // Create virtual gradient set for this item
                    String virtualSetId = "TEXTURE_SET_" + item.Id;
                    GRADIENT_SETS.put(virtualSetId, new ArrayList<>(item.Textures.keySet()));
                    ITEM_GRADIENT_MAP.put(item.Id, virtualSetId);
                } else {
                    // No explicit color source? Assume default white or handle gracefully in generation
                    // Some items like Faces don't have colors
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load category " + path + ": " + e.getMessage());
        }
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
        String skinTone = pickColor("Skin");
        if (skinTone == null) skinTone = "04"; // Fallback

        // Mandatory - No Color (Strings)
        cosmetics.put("FACE", getRandom(FACES));
        cosmetics.put("MOUTH", getRandom(MOUTHS));
        cosmetics.put("EARS", getRandom(EARS));

        // Mandatory - Needs Color
        // Body usually uses "Default" or "Muscular" from BodyCharacteristics + Skin Tone
        String bodyId = getRandom(BODY_CHARACTERISTICS);
        if (bodyId == null) bodyId = "Default";
        cosmetics.put("BODY", bodyId + "." + skinTone);
        
        // Underwear usually uses "Boxer" or from list + Colored_Cotton
        cosmetics.put("UNDERWEAR", generateItemWithColor(UNDERWEAR));

        // Hair items
        cosmetics.put("HAIR", generateItemWithColor(HAIRCUTS));
        // Eyebrows use Hair gradient usually, map handles it
        cosmetics.put("EYEBROWS", generateItemWithColor(EYEBROWS));
        // Eyes
        cosmetics.put("EYES", generateItemWithColor(EYES));

        // Clothing
        cosmetics.put("PANTS", generateItemWithColor(PANTS));
        cosmetics.put("SHOES", generateItemWithColor(SHOES));

        // Optional
        if (RANDOM.nextBoolean()) cosmetics.put("OVERTOP", generateItemWithColor(OVERTOPS));
        if (RANDOM.nextDouble() < 0.9) cosmetics.put("UNDERTOP", generateItemWithColor(UNDERTOPS));
        if (RANDOM.nextDouble() < 0.5) cosmetics.put("OVERPANTS", generateItemWithColor(OVERPANTS));
        if (RANDOM.nextDouble() < 0.2) cosmetics.put("CAPE", generateItemWithColor(CAPES));
        if (RANDOM.nextDouble() < 0.3) cosmetics.put("HEAD_ACCESSORY", generateItemWithColor(HEAD_ACCESSORY));
        if (RANDOM.nextDouble() < 0.2) cosmetics.put("FACE_ACCESSORY", generateItemWithColor(FACE_ACCESSORY));
        if (RANDOM.nextDouble() < 0.2) cosmetics.put("EAR_ACCESSORY", generateItemWithColor(EAR_ACCESSORY));
        if (RANDOM.nextDouble() < 0.3) cosmetics.put("FACIAL_HAIR", generateItemWithColor(FACIAL_HAIR));
        if (RANDOM.nextDouble() < 0.3) cosmetics.put("GLOVES", generateItemWithColor(GLOVES));

        return GSON.toJson(cosmetics);
    }

    private static String generateItemWithColor(List<String> items) {
        String item = getRandom(items);
        if (item == null) return null;
        
        String gradientSet = ITEM_GRADIENT_MAP.get(item);
        
        // If no gradient map, it might be a String-only field (like Face) or use default
        // But for clothing, they usually have one.
        // If null, we check if it needs one? 
        // Most items loaded have either GradientSet or Textures.
        // If they have neither, they might not take a color (e.g. Faces).
        // But generateItemWithColor is called for fields that REQUIRE color in schema.
        
        if (gradientSet != null) {
            String color = pickColor(gradientSet);
            if (color != null) {
                return item + "." + color;
            }
        }
        
        // Fallback: If no gradient set found but called here, assume Colored_Cotton or similar?
        // Or if the item truly has no color variants, maybe return just ID?
        // But Schema requires ID.Color for these fields.
        // Safest fallback: Colored_Cotton White
        // Or check if "Colored_Cotton" exists (loaded from file).
        
        String fallbackColor = pickColor("Colored_Cotton");
        if (fallbackColor == null) fallbackColor = "White";
        
        return item + "." + fallbackColor;
    }

    private static String pickColor(String gradientSetId) {
        List<String> colors = GRADIENT_SETS.get(gradientSetId);
        if (colors == null || colors.isEmpty()) return null;
        return colors.get(RANDOM.nextInt(colors.size()));
    }

    private static String getRandom(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        return list.get(RANDOM.nextInt(list.size()));
    }

    // --- JSON Definition Classes ---
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
