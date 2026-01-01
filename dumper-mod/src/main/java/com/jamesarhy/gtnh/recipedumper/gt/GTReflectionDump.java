package com.jamesarhy.gtnh.recipedumper.gt;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.block.Block;
import net.minecraft.util.StatCollector;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;
import net.minecraftforge.fluids.FluidStack;

import java.lang.reflect.*;
import java.util.*;

public final class GTReflectionDump {

    private static final String[] RECIPE_MAPS_CANDIDATES = new String[] {
            "gregtech.api.recipe.RecipeMaps",
            "gtPlusPlus.api.recipe.GTPPRecipeMaps"
    };
    private static final String MACHINE_ID_BLAST_FURNACE = "gt.recipe.blastfurnace";
    private static final String META_TILE_NAME_ELECTRIC_BLAST_FURNACE = "multimachine.blastfurnace";
    private static final String DISPLAY_NAME_ELECTRIC_BLAST_FURNACE = "Electric Blast Furnace";
    private static final String CLASS_TOKEN_ELECTRIC_BLAST_FURNACE = "MTEElectricBlastFurnace";
    private static final String[] BONUS_DEBUG_KEYWORDS = new String[] {
            "parallel", "speed", "efficien", "coil", "bonus", "tier", "eut", "discount"
    };

    private interface RecipeProvider {
        List dumpRecipeMaps();
    }

    public static List<DumpRecipeMap> dumpAllRecipeMapsWithProviders() {
        List out = new ArrayList();
        List providers = getRecipeProviders();
        for (int i = 0; i < providers.size(); i++) {
            RecipeProvider provider = (RecipeProvider) providers.get(i);
            try {
                List maps = provider.dumpRecipeMaps();
                if (maps != null && maps.size() > 0) out.addAll(maps);
            } catch (Throwable t) {
                System.out.println("[recipedumper] Provider failed: " + provider.getClass().getName() + ": " + t);
            }
        }

        sortRecipeMaps(out);
        //noinspection unchecked
        return (List<DumpRecipeMap>) out;
    }

    public static List<DumpRecipeMap> dumpAllRecipeMaps() {
        List out = new ArrayList(); // List<DumpRecipeMap>

        List mapClasses = loadAll(RECIPE_MAPS_CANDIDATES);
        if (mapClasses == null || mapClasses.size() == 0) {
            System.out.println("[recipedumper] RecipeMaps classes not found");
            return Collections.emptyList();
        }

        for (int c = 0; c < mapClasses.size(); c++) {
            Class mapsClass = (Class) mapClasses.get(c);
            Field[] fields = mapsClass.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                try {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);

                    Object mapObj = f.get(null);
                    if (mapObj == null) continue;

                    String cn = mapObj.getClass().getName();
                    if (cn.indexOf("RecipeMap") < 0 && cn.indexOf("GT_Recipe_Map") < 0) continue;

                    DumpRecipeMap map = new DumpRecipeMap();
                    map.declaringField = mapsClass.getName() + "." + f.getName();
                    map.machineId = bestMachineId(mapObj, f.getName());
                    map.displayName = bestDisplayName(mapObj, f.getName(), map.machineId);
                    populateMachineBonuses(mapObj, map);

                    Collection recipes = getRecipesFromMap(mapObj);
                    map.recipeCount = recipes.size();
                    map.recipes = new ArrayList(); // List<DumpRecipe>

                    Iterator it = recipes.iterator();
                    while (it.hasNext()) {
                        Object rObj = it.next();
                        DumpRecipe r = dumpRecipe(rObj, map.machineId);
                        if (r != null) map.recipes.add(r);
                    }

                    ensureUniqueRids(map.recipes);
                    out.add(map);

                } catch (Throwable t) {
                    System.out.println("[recipedumper] Failed map " + f.getName() + ": " + t);
                }
            }
        }

        sortRecipeMaps(out);
        //noinspection unchecked
        return (List<DumpRecipeMap>) out;
    }

    private static void sortRecipeMaps(List maps) {
        Collections.sort(maps, new Comparator() {
            public int compare(Object oa, Object ob) {
                DumpRecipeMap a = (DumpRecipeMap) oa;
                DumpRecipeMap b = (DumpRecipeMap) ob;
                String am = (a == null || a.machineId == null) ? "" : a.machineId;
                String bm = (b == null || b.machineId == null) ? "" : b.machineId;
                return am.compareTo(bm);
            }
        });
    }

    private static List getRecipeProviders() {
        List providers = new ArrayList();
        providers.add(new GTRecipeMapProvider());
        providers.add(new VanillaCraftingProvider());
        providers.add(new VanillaSmeltingProvider());
        providers.add(new RailcraftProvider());
        return providers;
    }

    private static final class GTRecipeMapProvider implements RecipeProvider {
        public List dumpRecipeMaps() {
            return dumpAllRecipeMaps();
        }
    }

    private static final class VanillaCraftingProvider implements RecipeProvider {
        public List dumpRecipeMaps() {
            return dumpVanillaCraftingRecipeMaps();
        }
    }

    private static final class VanillaSmeltingProvider implements RecipeProvider {
        public List dumpRecipeMaps() {
            return dumpVanillaSmeltingRecipeMaps();
        }
    }

    private static final class RailcraftProvider implements RecipeProvider {
        public List dumpRecipeMaps() {
            return dumpRailcraftRecipeMaps();
        }
    }

    private static List dumpVanillaCraftingRecipeMaps() {
        List out = new ArrayList();
        List recipes = null;
        try {
            recipes = CraftingManager.getInstance().getRecipeList();
        } catch (Throwable t) {
            System.out.println("[recipedumper] CraftingManager not accessible: " + t);
        }
        if (recipes == null || recipes.size() == 0) return out;

        DumpRecipeMap map = new DumpRecipeMap();
        map.machineId = "minecraft:crafting";
        map.displayName = firstNonNull(
                bestLocalizedName("tile.workbench.name"),
                bestLocalizedName("container.crafting"),
                "Crafting"
        );
        map.declaringField = "provider:vanilla.crafting";
        map.recipes = new ArrayList();

        for (int i = 0; i < recipes.size(); i++) {
            Object rObj = recipes.get(i);
            DumpRecipe r = dumpCraftingRecipe(rObj, map.machineId);
            if (r != null) map.recipes.add(r);
        }

        map.recipeCount = map.recipes.size();
        out.add(map);
        return out;
    }

    private static List dumpVanillaSmeltingRecipeMaps() {
        List out = new ArrayList();
        Map smeltMap = null;
        try {
            smeltMap = FurnaceRecipes.smelting().getSmeltingList();
        } catch (Throwable t) {
            System.out.println("[recipedumper] FurnaceRecipes not accessible: " + t);
        }
        if (smeltMap == null || smeltMap.size() == 0) return out;

        DumpRecipeMap map = new DumpRecipeMap();
        map.machineId = "minecraft:smelting";
        map.displayName = firstNonNull(
                bestLocalizedName("tile.furnace.name"),
                "Furnace"
        );
        map.declaringField = "provider:vanilla.smelting";
        map.recipes = new ArrayList();

        Iterator it = smeltMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            Object inObj = entry.getKey();
            Object outObj = entry.getValue();
            DumpRecipe r = dumpSmeltingRecipe(inObj, outObj, map.machineId);
            if (r != null) map.recipes.add(r);
        }

        map.recipeCount = map.recipes.size();
        out.add(map);
        return out;
    }

    private static List dumpRailcraftRecipeMaps() {
        List out = new ArrayList();
        Class mgrClass = loadFirst(new String[] {
                "mods.railcraft.api.crafting.RailcraftCraftingManager",
                "mods.railcraft.common.crafting.RailcraftCraftingManager"
        });
        if (mgrClass == null) return out;

        Map alphaTags = railcraftAlphaTagsByNormalizedName();
        Field[] fields = mgrClass.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            Field f = fields[i];
            try {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object manager = f.get(null);
                if (manager == null) continue;

                String machineId = railcraftAlphaTagFromField(alphaTags, f.getName());
                if (!isUsableName(machineId)) continue;

                DumpRecipeMap map = dumpRailcraftManager(
                        manager,
                        machineId,
                        firstNonNull(
                                bestLocalizedName(machineId),
                                humanizeRailcraftTag(machineId),
                                "Railcraft"
                        ),
                        "provider:railcraft." + f.getName()
                );
                if (map != null) out.add(map);
            } catch (Throwable ignored) {}
        }

        return out;
    }

    private static DumpRecipeMap dumpRailcraftManager(Object manager, String machineId, String displayName, String declaringField) {
        if (manager == null) return null;
        Object recipesObj = tryInvokeAny(manager, new String[] {"getRecipes", "getRecipeList", "getRecipesList"});
        if (recipesObj == null) recipesObj = getAny(manager, new String[] {"recipes", "recipeList"});

        Collection recipes = null;
        if (recipesObj instanceof Collection) {
            recipes = (Collection) recipesObj;
        } else if (recipesObj instanceof Map) {
            recipes = ((Map) recipesObj).values();
        }
        if (recipes == null || recipes.size() == 0) return null;

        DumpRecipeMap map = new DumpRecipeMap();
        map.machineId = machineId;
        map.displayName = displayName;
        map.declaringField = declaringField;
        map.recipes = new ArrayList();

        Iterator it = recipes.iterator();
        while (it.hasNext()) {
            Object rObj = it.next();
            DumpRecipe r = dumpRailcraftRecipe(rObj, machineId);
            if (r != null) map.recipes.add(r);
        }

        map.recipeCount = map.recipes.size();
        return map;
    }

    private static DumpRecipe dumpCraftingRecipe(Object rObj, String machineId) {
        if (!(rObj instanceof IRecipe)) return null;
        IRecipe recipe = (IRecipe) rObj;
        ItemStack output = null;
        try {
            output = recipe.getRecipeOutput();
        } catch (Throwable ignored) {}
        if (output == null) return null;

        List inputs = extractCraftingInputs(rObj);
        if (inputs == null || inputs.size() == 0) return null;

        DumpRecipe r = new DumpRecipe();
        r.machineId = machineId;
        r.recipeClass = rObj.getClass().getName();
        r.durationTicks = 0;
        r.eut = 0;
        r.itemInputs = inputs;
        r.itemOutputs = dumpItemStacks(output);
        r.fluidInputs = new ArrayList();
        r.fluidOutputs = new ArrayList();
        r.rid = stableRid(machineId, r);
        return r;
    }

    private static DumpRecipe dumpSmeltingRecipe(Object inObj, Object outObj, String machineId) {
        if (inObj == null || outObj == null) return null;
        DumpItemStack in = dumpIngredient(inObj);
        if (in == null) return null;
        List inputs = new ArrayList();
        inputs.add(in);

        List outputs = dumpItemStacks(outObj);
        if (outputs == null || outputs.size() == 0) return null;

        DumpRecipe r = new DumpRecipe();
        r.machineId = machineId;
        r.recipeClass = (outObj != null) ? outObj.getClass().getName() : "smelting";
        r.durationTicks = 200;
        r.eut = 0;
        r.itemInputs = inputs;
        r.itemOutputs = outputs;
        r.fluidInputs = new ArrayList();
        r.fluidOutputs = new ArrayList();
        r.rid = stableRid(machineId, r);
        return r;
    }

    private static DumpRecipe dumpRailcraftRecipe(Object rObj, String machineId) {
        if (rObj == null) return null;
        Object inputObj = tryInvokeAny(rObj, new String[] {"getInput", "getInputs", "getInputStack"});
        if (inputObj == null) inputObj = getAny(rObj, new String[] {"input", "inputs"});
        List inputs = dumpIngredientList(inputObj);
        if (inputs == null || inputs.size() == 0) return null;

        Object outputObj = tryInvokeAny(rObj, new String[] {"getOutput", "getOutputStack", "getResult"});
        if (outputObj == null) outputObj = getAny(rObj, new String[] {"output", "result"});
        List outputs = dumpItemStacks(outputObj);
        if (outputs == null || outputs.size() == 0) return null;

        Object fluidOutObj = tryInvokeAny(rObj, new String[] {"getFluidOutput", "getOutputFluid", "getFluid"});
        if (fluidOutObj == null) fluidOutObj = getAny(rObj, new String[] {"fluid", "fluidOutput", "outputFluid"});
        List fluidOutputs = dumpFluids(fluidOutObj);

        int duration = 0;
        Object durObj = tryInvokeAny(rObj, new String[] {"getTime", "getCookTime", "getDuration", "getCookTimeTicks"});
        if (durObj == null) durObj = getAny(rObj, new String[] {"time", "cookTime", "duration"});
        if (durObj instanceof Number) duration = ((Number) durObj).intValue();

        DumpRecipe r = new DumpRecipe();
        r.machineId = machineId;
        r.recipeClass = rObj.getClass().getName();
        r.durationTicks = duration;
        r.eut = 0;
        r.itemInputs = inputs;
        r.itemOutputs = outputs;
        r.fluidInputs = new ArrayList();
        r.fluidOutputs = (fluidOutputs == null) ? new ArrayList() : fluidOutputs;
        r.rid = stableRid(machineId, r);
        return r;
    }

    private static List extractCraftingInputs(Object rObj) {
        Object input = null;
        try {
            if (rObj instanceof ShapedRecipes) {
                input = ((ShapedRecipes) rObj).recipeItems;
            } else if (rObj instanceof ShapelessRecipes) {
                input = ((ShapelessRecipes) rObj).recipeItems;
            } else if (rObj instanceof ShapedOreRecipe) {
                input = ((ShapedOreRecipe) rObj).getInput();
            } else if (rObj instanceof ShapelessOreRecipe) {
                input = ((ShapelessOreRecipe) rObj).getInput();
            }
        } catch (Throwable ignored) {}

        if (input == null) {
            input = tryInvokeAny(rObj, new String[] {"getInput", "getInputs", "getIngredients", "getRecipeItems"});
        }
        if (input == null) {
            input = getAny(rObj, new String[] {"input", "inputs", "ingredients", "recipeItems"});
        }

        return dumpIngredientList(input);
    }

    private static List dumpIngredientList(Object input) {
        if (input == null) return null;
        Map counts = new LinkedHashMap();
        if (input instanceof List) {
            List list = (List) input;
            for (int i = 0; i < list.size(); i++) {
                addIngredientCount(counts, list.get(i));
            }
        } else if (input.getClass().isArray()) {
            int len = Array.getLength(input);
            for (int i = 0; i < len; i++) {
                addIngredientCount(counts, Array.get(input, i));
            }
        } else {
            addIngredientCount(counts, input);
        }
        if (counts.size() == 0) return null;
        return new ArrayList(counts.values());
    }

    private static void addIngredientCount(Map counts, Object obj) {
        if (obj == null) return;
        if (obj instanceof Character) return;
        DumpItemStack st = dumpIngredient(obj);
        if (st == null) return;
        String key = st.id + "|" + st.meta;
        DumpItemStack existing = (DumpItemStack) counts.get(key);
        if (existing == null) {
            counts.put(key, st);
        } else {
            existing.count += st.count;
        }
    }

    private static DumpItemStack dumpIngredient(Object obj) {
        if (obj == null) return null;
        if (obj instanceof ItemStack) {
            ItemStack st = (ItemStack) obj;
            DumpItemStack d = new DumpItemStack();
            d.id = itemKey(st);
            d.count = Math.max(1, st.stackSize);
            d.meta = st.getItemDamage();
            d.displayName = safeDisplayName(st);
            d.unlocalizedName = safeUnlocName(st);
            d.oreDict = oreDictNames(st);
            return d;
        }
        if (obj instanceof Item) {
            return dumpIngredient(new ItemStack((Item) obj));
        }
        if (obj instanceof Block) {
            return dumpIngredient(new ItemStack((Block) obj));
        }
        if (obj instanceof String) {
            return oreIngredient((String) obj, 1);
        }
        if (obj instanceof List) {
            return dumpOreList((List) obj);
        }
        return null;
    }

    private static DumpItemStack dumpOreList(List list) {
        if (list == null || list.size() == 0) return null;
        String name = findOreNameForList(list);
        if (name != null) return oreIngredient(name, 1);
        Object first = list.get(0);
        if (first instanceof ItemStack) {
            int[] ids = OreDictionary.getOreIDs((ItemStack) first);
            if (ids != null && ids.length > 0) {
                return oreIngredient(OreDictionary.getOreName(ids[0]), 1);
            }
        }
        return oreIngredient("unresolved", 1);
    }

    private static DumpItemStack oreIngredient(String name, int count) {
        DumpItemStack d = new DumpItemStack();
        d.id = "ore:" + name;
        d.count = Math.max(1, count);
        d.meta = 0;
        return d;
    }

    private static String findOreNameForList(List list) {
        if (list == null || list.size() == 0) return null;
        Object first = list.get(0);
        if (!(first instanceof ItemStack)) return null;
        ItemStack st = (ItemStack) first;
        int[] ids = OreDictionary.getOreIDs(st);
        if (ids != null) {
            for (int i = 0; i < ids.length; i++) {
                String name = OreDictionary.getOreName(ids[i]);
                List ores = OreDictionary.getOres(name);
                if (ores == list) return name;
                if (oreListMatches(ores, list)) return name;
            }
        }
        return null;
    }

    private static boolean oreListMatches(List a, List b) {
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Object ao = a.get(i);
            if (!(ao instanceof ItemStack)) return false;
            if (!containsItemStack(b, (ItemStack) ao)) return false;
        }
        return true;
    }

    private static boolean containsItemStack(List list, ItemStack target) {
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (!(o instanceof ItemStack)) continue;
            if (itemStackEquals((ItemStack) o, target)) return true;
        }
        return false;
    }

    private static boolean itemStackEquals(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getItem() != b.getItem()) return false;
        int am = a.getItemDamage();
        int bm = b.getItemDamage();
        if (am == bm) return true;
        return am == OreDictionary.WILDCARD_VALUE || bm == OreDictionary.WILDCARD_VALUE;
    }

    private static String firstNonNull(String a, String b, String c) {
        if (isUsableName(a)) return a;
        if (isUsableName(b)) return b;
        if (isUsableName(c)) return c;
        return null;
    }

    private static String firstNonNull(String a, String b) {
        if (isUsableName(a)) return a;
        if (isUsableName(b)) return b;
        return null;
    }

    public static List<DumpMachineIndex> dumpMachineIndexFromMetaTiles() {
        Class apiClass = loadFirst(new String[] {"gregtech.api.GregTechAPI"});
        if (apiClass == null) {
            System.out.println("[recipedumper] GregTechAPI not found");
            return Collections.emptyList();
        }

        Object arrObj = null;
        try {
            Field f = apiClass.getDeclaredField("METATILEENTITIES");
            f.setAccessible(true);
            arrObj = f.get(null);
        } catch (Throwable t) {
            System.out.println("[recipedumper] GregTechAPI.METATILEENTITIES not accessible: " + t);
        }

        if (arrObj == null || !arrObj.getClass().isArray()) return Collections.emptyList();

        int len = Array.getLength(arrObj);
        List out = new ArrayList();

        for (int i = 0; i < len; i++) {
            Object mte = Array.get(arrObj, i);
            if (mte == null) continue;

            DumpMachineIndex d = new DumpMachineIndex();
            d.metaTileId = new Integer(i);
            d.metaTileClass = mte.getClass().getName();
            d.metaTileName = bestMetaTileName(mte);
            d.displayName = bestDisplayNameFromMetaTile(mte);

            Object mapObj = resolveRecipeMap(mte);
            if (mapObj != null) {
                d.machineId = bestMachineId(mapObj, "meta." + i);
            }

            Object logic = tryCreateProcessingLogic(mte);
            if (mapObj == null && !hasAvailableRecipeMaps(mte) && logic == null) continue;

            Object bonusSource = bestBonusSource(mte);
            d.parallelBonus = readNumberFromAny(bonusSource, new String[] {
                    "getParallelBonus", "getParallelMultiplier", "parallelBonus", "mParallelBonus",
                    "parallelMultiplier", "mParallelMultiplier", "mParallelProcessing", "parallelProcessing"
            });
            d.maxParallel = readNumberFromAny(bonusSource, new String[] {
                    "getMaxParallel", "getParallelLimit", "maxParallel", "mMaxParallel",
                    "parallelLimit", "mParallelLimit", "getMaxParallelRecipes", "maxParallelRecipes"
            });
            d.coilBonus = readNumberFromAny(bonusSource, new String[] {
                    "getCoilBonus", "coilBonus", "mCoilBonus", "coilSpeedBonus", "mCoilSpeedBonus",
                    "coilLevel", "mCoilLevel", "getCoilLevel"
            });
            d.speedBonus = readNumberFromAny(bonusSource, new String[] {
                    "getSpeedBonus", "speedBonus", "mSpeedBonus", "speedMultiplier", "mSpeedMultiplier"
            });
            d.efficiencyBonus = readNumberFromAny(bonusSource, new String[] {
                    "getEfficiencyBonus", "efficiencyBonus", "mEfficiencyBonus", "getEfficiency",
                    "efficiency", "mEfficiency", "getEUtDiscount", "eutDiscount", "mEUtDiscount"
            });

            populateBonusesFromProcessingLogic(mte, d, logic);
            Integer perTier = computeParallelPerTier(mte);
            if (perTier != null && (d.parallelBonus == null || d.parallelBonus.doubleValue() == 0.0)) {
                d.parallelBonus = new Double(perTier.doubleValue());
            }
            populateBonusesFromTooltip(mte, d);
            sanitizeBonusFields(d);
            roundBonusFields(d);

            out.add(d);
        }

        return out;
    }

    public static List<DumpMachineIndex> dumpMachineIndexFromRecipeMaps(List recipeMaps) {
        if (recipeMaps == null || recipeMaps.size() == 0) return Collections.emptyList();
        List out = new ArrayList();
        for (int i = 0; i < recipeMaps.size(); i++) {
            Object o = recipeMaps.get(i);
            if (!(o instanceof DumpRecipeMap)) continue;
            DumpRecipeMap map = (DumpRecipeMap) o;
            if (!isUsableName(map.machineId)) continue;
            DumpMachineIndex d = new DumpMachineIndex();
            d.machineId = map.machineId;
            d.displayName = map.displayName;
            d.parallelBonus = map.parallelBonus;
            d.maxParallel = map.maxParallel;
            d.coilBonus = map.coilBonus;
            d.speedBonus = map.speedBonus;
            d.efficiencyBonus = map.efficiencyBonus;
            d.tooltipDerived = map.tooltipDerived;
            out.add(d);
        }
        return out;
    }

    public static List<DumpMachineIndex> mergeMachineIndexWithRecipeMaps(List metaTiles, List recipeMaps) {
        Map mapById = new LinkedHashMap();
        if (recipeMaps != null) {
            for (int i = 0; i < recipeMaps.size(); i++) {
                Object o = recipeMaps.get(i);
                if (!(o instanceof DumpMachineIndex)) continue;
                DumpMachineIndex d = (DumpMachineIndex) o;
                if (!isUsableName(d.machineId)) continue;
                mapById.put(d.machineId, d);
            }
        }

        List out = new ArrayList();
        Set seen = new HashSet();
        if (metaTiles != null) {
            for (int i = 0; i < metaTiles.size(); i++) {
                Object o = metaTiles.get(i);
                if (!(o instanceof DumpMachineIndex)) continue;
                DumpMachineIndex d = (DumpMachineIndex) o;
                DumpMachineIndex map = (DumpMachineIndex) mapById.get(d.machineId);
                if (map != null) {
                    if (shouldReplaceDisplayName(d.displayName, map.displayName)) {
                        d.displayName = map.displayName;
                    }
                    if (d.parallelBonus == null && map.parallelBonus != null) d.parallelBonus = map.parallelBonus;
                    if (d.maxParallel == null && map.maxParallel != null) d.maxParallel = map.maxParallel;
                    if (d.coilBonus == null && map.coilBonus != null) d.coilBonus = map.coilBonus;
                    if (d.speedBonus == null && map.speedBonus != null) d.speedBonus = map.speedBonus;
                    if (d.efficiencyBonus == null && map.efficiencyBonus != null) d.efficiencyBonus = map.efficiencyBonus;
                    if (d.tooltipDerived == null && map.tooltipDerived != null) d.tooltipDerived = map.tooltipDerived;
                }
                out.add(d);
                if (isUsableName(d.machineId)) seen.add(d.machineId);
            }
        }

        if (recipeMaps != null) {
            for (int i = 0; i < recipeMaps.size(); i++) {
                Object o = recipeMaps.get(i);
                if (!(o instanceof DumpMachineIndex)) continue;
                DumpMachineIndex d = (DumpMachineIndex) o;
                if (!isUsableName(d.machineId)) continue;
                if (seen.contains(d.machineId)) continue;
                out.add(d);
            }
        }

        preferElectricBlastFurnace(out);
        return out;
    }

    private static void preferElectricBlastFurnace(List out) {
        if (out == null) return;
        int firstIndex = -1;
        int preferredIndex = -1;
        for (int i = 0; i < out.size(); i++) {
            Object o = out.get(i);
            if (!(o instanceof DumpMachineIndex)) continue;
            DumpMachineIndex d = (DumpMachineIndex) o;
            if (!MACHINE_ID_BLAST_FURNACE.equals(d.machineId)) continue;
            if (firstIndex < 0) firstIndex = i;
            if (preferredIndex < 0 && isElectricBlastFurnace(d)) preferredIndex = i;
            if (firstIndex >= 0 && preferredIndex >= 0) break;
        }
        if (firstIndex >= 0 && preferredIndex >= 0 && preferredIndex != firstIndex) {
            Object preferred = out.remove(preferredIndex);
            out.add(firstIndex, preferred);
        }
    }

    private static boolean isElectricBlastFurnace(DumpMachineIndex d) {
        if (d == null) return false;
        if (!MACHINE_ID_BLAST_FURNACE.equals(d.machineId)) return false;
        if (DISPLAY_NAME_ELECTRIC_BLAST_FURNACE.equals(d.displayName)) return true;
        if (META_TILE_NAME_ELECTRIC_BLAST_FURNACE.equals(d.metaTileName)) return true;
        if (d.metaTileClass != null && d.metaTileClass.indexOf(CLASS_TOKEN_ELECTRIC_BLAST_FURNACE) >= 0) return true;
        return false;
    }

    public static List<DumpMachineIndexDebug> dumpMachineIndexDebugFromMetaTiles() {
        Class apiClass = loadFirst(new String[] {"gregtech.api.GregTechAPI"});
        if (apiClass == null) {
            System.out.println("[recipedumper] GregTechAPI not found");
            return Collections.emptyList();
        }

        Object arrObj = null;
        try {
            Field f = apiClass.getDeclaredField("METATILEENTITIES");
            f.setAccessible(true);
            arrObj = f.get(null);
        } catch (Throwable t) {
            System.out.println("[recipedumper] GregTechAPI.METATILEENTITIES not accessible: " + t);
        }

        if (arrObj == null || !arrObj.getClass().isArray()) return Collections.emptyList();

        int len = Array.getLength(arrObj);
        List out = new ArrayList();

        for (int i = 0; i < len; i++) {
            Object mte = Array.get(arrObj, i);
            if (mte == null) continue;

            String className = mte.getClass().getName();
            String metaName = bestMetaTileName(mte);
            String[] displaySource = new String[1];
            String displayName = bestDisplayNameFromMetaTile(mte, displaySource);

            if (!isDebugTarget(className, metaName, displayName)) continue;

            DumpMachineIndexDebug d = new DumpMachineIndexDebug();
            d.metaTileId = new Integer(i);
            d.metaTileClass = className;
            d.metaTileName = metaName;
            d.displayName = displayName;
            d.displayNameSource = (displaySource != null && displaySource.length > 0) ? displaySource[0] : null;
            d.displayNameCandidates = collectDisplayNameCandidates(mte);

            Object mapObj = tryInvokeAny(mte, new String[] {"getRecipeMap", "getRecipeMapForNEI", "getRecipeMapNEI"});
            if (mapObj == null) {
                mapObj = getAny(mte, new String[] {"mRecipeMap", "recipeMap", "mMap"});
            }
            if (mapObj != null) {
                d.machineId = bestMachineId(mapObj, "meta." + i);
            }

            d.metaTileFields = collectNumericFields(mte);
            d.metaTileMethods = collectNumericMethods(mte);

            Object controller = bestBonusSource(mte);
            if (controller != mte && controller != null) {
                d.controllerClass = controller.getClass().getName();
                d.controllerFields = collectNumericFields(controller);
                d.controllerMethods = collectNumericMethods(controller);
            }

            out.add(d);
        }

        return out;
    }

    private static DumpRecipe dumpRecipe(Object rObj, String machineId) {
        try {
            DumpRecipe r = new DumpRecipe();
            r.machineId = machineId;
            r.recipeClass = rObj.getClass().getName();

            r.durationTicks = asInt(getAny(rObj, new String[] {"mDuration", "duration", "durationTicks"}));
            r.eut = asInt(getAny(rObj, new String[] {"mEUt", "EUt", "eut", "mEU"}));

            addPowerDerivedFields(r);

            r.specialValue = asInteger(getAny(rObj, new String[] {"mSpecialValue", "mSpecial", "specialValue"}));

            // Interpret specialValue for EBF recipes
            if (machineId != null && machineId.indexOf("blastfurnace") >= 0) {
                // In GTNH/GT5u-style, blast furnace temperature is typically stored here.
                if (r.specialValue != null && r.specialValue.intValue() > 0) {
                    r.ebfTemp = r.specialValue;
                }
            }

            r.itemInputs = dumpItemStacks(getAny(rObj, new String[] {"mInputs", "inputs", "mInput"}));

            extractGhostCircuit(r);

            r.itemOutputs = dumpItemStacks(getAny(rObj, new String[] {"mOutputs", "outputs", "mOutput"}));

            r.fluidInputs = dumpFluids(getAny(rObj, new String[] {"mFluidInputs", "fluidInputs", "mFluidInput"}));
            r.fluidOutputs = dumpFluids(getAny(rObj, new String[] {"mFluidOutputs", "fluidOutputs", "mFluidOutput"}));

            int[] chances = asIntArray(getAny(rObj, new String[] {"mChances", "chances", "outputChances"}));
            if (chances != null) {
                r.outputChances = toIntList(chances);
                r.chanceScale = guessChanceScale(chances);
            }

            applyOutputChancesToItemOutputs(r);

            r.rid = stableRid(machineId, r);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------- Map identity (NEI-like) ---------- */

    private static String bestMachineId(Object mapObj, String fallbackFieldName) {
        Object s = tryInvokeAny(mapObj, new String[] {"getUnlocalizedName", "getName", "getID"});
        if (s instanceof String && ((String) s).length() > 0) return (String) s;

        Object f = getAny(mapObj, new String[] {"mUnlocalizedName", "mName", "mNEIName", "unlocalizedName", "name"});
        if (f instanceof String && ((String) f).length() > 0) return (String) f;

        return "gt.map." + fallbackFieldName;
    }

    private static String bestDisplayName(Object mapObj, String fallbackFieldName, String machineId) {
        String name = bestDisplayNameFromBlock(mapObj);
        if (isUsableName(name)) return name;

        name = localizedName(machineId);
        if (isUsableName(name)) return name;

        Object rawName = tryInvokeAny(mapObj, new String[] {"getUnlocalizedName", "getName", "getID"});
        if (rawName instanceof String) {
            name = localizedName((String) rawName);
            if (isUsableName(name)) return name;
        }

        Object locName = tryInvokeAny(mapObj, new String[] {"getLocalizedName", "getDisplayName"});
        if (locName instanceof String && ((String) locName).length() > 0) return (String) locName;

        Object f = getAny(mapObj, new String[] {"mNEIName", "mName", "displayName", "name"});
        if (f instanceof String && ((String) f).length() > 0) return (String) f;

        return fallbackFieldName;
    }

    private static void extractGhostCircuit(DumpRecipe r) {
        if (r.itemInputs == null) return;

        for (int i = 0; i < r.itemInputs.size(); i++) {
            DumpItemStack in = (DumpItemStack) r.itemInputs.get(i);
            if (in == null || in.id == null) continue;

            // GTNH typically: item:gregtech:gt.integrated_circuit
            // Be tolerant: match by substring.
            boolean isCircuit = (in.id.indexOf("integrated_circuit") >= 0)
                    || (in.id.indexOf("programmed") >= 0 && in.id.indexOf("circuit") >= 0);

            if (!isCircuit) continue;

            // Many GT recipes store it as a ghost with count==0.
            // Even if count>0, treat it as config input (you can decide later).
            r.circuitConfig = new Integer(in.meta);
            r.circuitGhost = (in.count == 0) ? Boolean.TRUE : Boolean.FALSE;

            // Optional: remove it from itemInputs so your "real inputs" are clean
            // If you prefer to keep it, comment this out.
            r.itemInputs.remove(i);
            return;
        }
    }

    /* ---------- Recipe list extraction ---------- */

    private static Collection getRecipesFromMap(Object mapObj) {
        Object v = tryInvokeAny(mapObj, new String[] {"getAllRecipes", "getRecipes", "getRecipeList", "values"});
        if (v instanceof Map) return ((Map) v).values();
        if (v instanceof Collection) return (Collection) v;

        Field[] fs = mapObj.getClass().getDeclaredFields();
        for (int i = 0; i < fs.length; i++) {
            try {
                fs[i].setAccessible(true);
                Object o = fs[i].get(mapObj);
                if (o instanceof Map) return ((Map) o).values();
                if (o instanceof Collection) return (Collection) o;
            } catch (Throwable ignored) {}
        }
        return Collections.emptyList();
    }

    /* ---------- Stack dumping ---------- */

    private static List dumpItemStacks(Object v) {
        List out = new ArrayList(); // List<DumpItemStack>
        if (v == null) return out;

        if (v instanceof ItemStack[]) {
            ItemStack[] arr = (ItemStack[]) v;
            for (int i = 0; i < arr.length; i++) addItem(out, arr[i]);
        } else if (v instanceof ItemStack) {
            addItem(out, (ItemStack) v);
        }
        return out;
    }

    private static void addItem(List out, ItemStack st) {
        if (st == null) return;
        DumpItemStack d = new DumpItemStack();
        d.id = itemKey(st);
        d.count = st.stackSize;
        d.meta = st.getItemDamage();
        d.displayName = safeDisplayName(st);       // <--- key
        d.unlocalizedName = safeUnlocName(st);     // optional
        d.oreDict = oreDictNames(st);              // next section
        out.add(d);
    }

    private static String safeDisplayName(ItemStack st) {
        try { return st.getDisplayName(); } catch (Throwable t) { return null; }
    }
    private static String safeUnlocName(ItemStack st) {
        try { return st.getUnlocalizedName(); } catch (Throwable t) { return null; }
    }

    private static List oreDictNames(ItemStack st) {
        try {
            int[] ids = net.minecraftforge.oredict.OreDictionary.getOreIDs(st);
            if (ids == null || ids.length == 0) return null;
            List out = new ArrayList();
            for (int i = 0; i < ids.length; i++) {
                out.add(net.minecraftforge.oredict.OreDictionary.getOreName(ids[i]));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static List dumpFluids(Object v) {
        List out = new ArrayList(); // List<DumpFluidStack>
        if (v == null) return out;

        if (v instanceof FluidStack[]) {
            FluidStack[] arr = (FluidStack[]) v;
            for (int i = 0; i < arr.length; i++) addFluid(out, arr[i]);
        } else if (v instanceof FluidStack) {
            addFluid(out, (FluidStack) v);
        }
        return out;
    }

    private static void addFluid(List out, FluidStack fs) {
        if (fs == null || fs.getFluid() == null) return;
        DumpFluidStack d = new DumpFluidStack();
        d.id = "fluid:" + fs.getFluid().getName();
        d.mb = fs.amount;
        d.isGas = isFluidGas(fs.getFluid());
        d.displayName = fluidDisplayName(fs);
        d.unlocalizedName = fluidUnlocalizedName(fs);
        out.add(d);
    }

    private static String fluidDisplayName(FluidStack fs) {
        if (fs == null || fs.getFluid() == null) return null;
        Object fluid = fs.getFluid();
        String v = invokeFluidString(fluid, "getLocalizedName", new Class[] { FluidStack.class }, new Object[] { fs });
        if (isUsableName(v)) return v;
        v = invokeFluidString(fluid, "getLocalizedName", new Class[0], new Object[0]);
        if (isUsableName(v)) return v;
        return null;
    }

    private static String fluidUnlocalizedName(FluidStack fs) {
        if (fs == null || fs.getFluid() == null) return null;
        Object fluid = fs.getFluid();
        String v = invokeFluidString(fluid, "getUnlocalizedName", new Class[] { FluidStack.class }, new Object[] { fs });
        if (isUsableName(v)) return v;
        v = invokeFluidString(fluid, "getUnlocalizedName", new Class[0], new Object[0]);
        if (isUsableName(v)) return v;
        return null;
    }

    private static String invokeFluidString(Object fluid, String methodName, Class[] argTypes, Object[] args) {
        if (fluid == null || methodName == null) return null;
        try {
            Method m = fluid.getClass().getMethod(methodName, argTypes);
            m.setAccessible(true);
            Object out = m.invoke(fluid, args);
            if (out instanceof String) return (String) out;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String itemKey(ItemStack st) {
        try {
            String name = net.minecraft.item.Item.itemRegistry.getNameForObject(st.getItem());
            return "item:" + (name == null ? "unknown" : name);
        } catch (Throwable t) {
            return "item:unknown";
        }
    }

    /* ---------- Reflection helpers ---------- */

    private static Class loadFirst(String[] names) {
        for (int i = 0; i < names.length; i++) {
            try {
                return Class.forName(names[i]);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static List<DumpMachineIndex> dumpMachineIndexFromRailcraftAlpha() {
        List out = new ArrayList();
        Class enumClass = loadFirst(new String[] {
                "mods.railcraft.common.blocks.machine.alpha.EnumMachineAlpha"
        });
        if (enumClass == null || !enumClass.isEnum()) return out;

        Object[] values = enumClass.getEnumConstants();
        if (values == null) return out;

        for (int i = 0; i < values.length; i++) {
            Object constant = values[i];
            Object tagObj = tryInvokeNoArg(constant, "getTag");
            if (!(tagObj instanceof String)) continue;
            String tag = (String) tagObj;
            if (!isUsableName(tag)) continue;

            DumpMachineIndex d = new DumpMachineIndex();
            d.machineId = tag;
            d.displayName = firstNonNull(
                    bestLocalizedName(tag),
                    humanizeRailcraftTag(tag)
            );

            Object tileClassObj = tryInvokeNoArg(constant, "getTileClass");
            if (tileClassObj instanceof Class) {
                d.metaTileClass = ((Class) tileClassObj).getName();
            }

            out.add(d);
        }
        return out;
    }

    private static String railcraftAlphaTagFromField(Map alphaTags, String fieldName) {
        if (!isUsableName(fieldName) || alphaTags == null) return null;
        String key = normalizeKey(fieldName);
        if (key.length() == 0) return null;
        Object tag = alphaTags.get(key);
        if (tag instanceof String && ((String) tag).length() > 0) return (String) tag;
        return null;
    }

    private static String humanizeRailcraftTag(String tag) {
        if (!isUsableName(tag)) return null;
        String shortTag = stripRailcraftAlphaPrefix(tag);
        if (!isUsableName(shortTag)) return null;

        StringBuilder out = new StringBuilder(shortTag.length());
        boolean newWord = true;
        for (int i = 0; i < shortTag.length(); i++) {
            char c = shortTag.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(newWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
                newWord = false;
            } else {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                newWord = true;
            }
        }
        String s = out.toString().trim();
        return (s.length() == 0) ? null : s;
    }

    private static Map railcraftAlphaTagsByNormalizedName() {
        Map out = new HashMap();
        Class enumClass = loadFirst(new String[] {
                "mods.railcraft.common.blocks.machine.alpha.EnumMachineAlpha"
        });
        if (enumClass == null || !enumClass.isEnum()) return out;

        Object[] values = enumClass.getEnumConstants();
        if (values == null) return out;

        for (int i = 0; i < values.length; i++) {
            Object constant = values[i];
            Object tagObj = tryInvokeNoArg(constant, "getTag");
            if (!(tagObj instanceof String)) continue;
            String tag = (String) tagObj;
            if (!isUsableName(tag)) continue;

            String fullKey = normalizeKey(tag);
            if (fullKey.length() > 0 && !out.containsKey(fullKey)) out.put(fullKey, tag);

            String shortTag = stripRailcraftAlphaPrefix(tag);
            String shortKey = normalizeKey(shortTag);
            if (shortKey.length() > 0 && !out.containsKey(shortKey)) out.put(shortKey, tag);
        }
        return out;
    }

    private static String stripRailcraftAlphaPrefix(String tag) {
        String prefix = "tile.railcraft.machine.alpha.";
        if (tag != null && tag.startsWith(prefix)) return tag.substring(prefix.length());
        return tag;
    }

    private static String normalizeKey(String s) {
        if (s == null || s.length() == 0) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    private static List loadAll(String[] names) {
        List out = new ArrayList();
        if (names == null) return out;
        for (int i = 0; i < names.length; i++) {
            try {
                out.add(Class.forName(names[i]));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static Object tryInvokeAny(Object o, String[] names) {
        for (int i = 0; i < names.length; i++) {
            try {
                Method m = o.getClass().getMethod(names[i], new Class[0]);
                m.setAccessible(true);
                return m.invoke(o, new Object[0]);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object tryInvokeNoArg(Object o, String name) {
        Method m = findNoArgMethod(o.getClass(), name);
        if (m == null) return null;
        try {
            m.setAccessible(true);
            return m.invoke(o, new Object[0]);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object getAny(Object o, String[] names) {
        Class c = o.getClass();
        while (c != null) {
            for (int i = 0; i < names.length; i++) {
                try {
                    Field f = c.getDeclaredField(names[i]);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static int asInt(Object o) {
        return (o instanceof Number) ? ((Number) o).intValue() : 0;
    }

    private static Integer asInteger(Object o) {
        return (o instanceof Number) ? new Integer(((Number) o).intValue()) : null;
    }

    private static int[] asIntArray(Object o) {
        return (o instanceof int[]) ? (int[]) o : null;
    }

    private static List toIntList(int[] a) {
        List out = new ArrayList(); // List<Integer>
        for (int i = 0; i < a.length; i++) out.add(new Integer(a[i]));
        return out;
    }

    private static Integer guessChanceScale(int[] a) {
        int max = 0;
        for (int i = 0; i < a.length; i++) if (a[i] > max) max = a[i];
        if (max <= 10000) return new Integer(10000);
        if (max <= 100000) return new Integer(100000);
        return null;
    }

    private static void applyOutputChancesToItemOutputs(DumpRecipe r) {
        if (r == null || r.itemOutputs == null || r.outputChances == null) return;
        if (r.itemOutputs.size() == 0 || r.outputChances.size() == 0) return;
        if (r.chanceScale == null || r.chanceScale.intValue() <= 0) return;

        int limit = Math.min(r.itemOutputs.size(), r.outputChances.size());
        double scale = r.chanceScale.doubleValue();
        for (int i = 0; i < limit; i++) {
            DumpItemStack out = (DumpItemStack) r.itemOutputs.get(i);
            if (out == null) continue;
            Object chObj = r.outputChances.get(i);
            if (!(chObj instanceof Number)) continue;
            double chance = ((Number) chObj).doubleValue() / scale;
            out.chance = new Double(chance);
        }
    }

    private static String stableRid(String machineId, DumpRecipe r) {
        String sig = buildSignature(machineId, r);
        String hex = sha1Hex(sig);
        return "gt:" + machineId + ":" + hex.substring(0, 12); // 12+ chars is plenty
    }

    private static String buildSignature(String machineId, DumpRecipe r) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("machine=").append(nullSafe(machineId));
        sb.append("|class=").append(nullSafe(r.recipeClass));
        sb.append("|dur=").append(r.durationTicks);
        sb.append("|eut=").append(r.eut);

        // Special / EBF
        sb.append("|special=").append(r.specialValue != null ? r.specialValue.intValue() : 0);

        // Ghost circuit (treat absent as -1 so it disambiguates cleanly)
        int cc = (r.circuitConfig != null) ? r.circuitConfig.intValue() : -1;
        sb.append("|circuit=").append(cc);

        // Inputs/outputs (sorted canonical)
        sb.append("|inItems=").append(canonItems(r.itemInputs));
        sb.append("|inFluids=").append(canonFluids(r.fluidInputs));
        sb.append("|outItems=").append(canonItems(r.itemOutputs));
        sb.append("|outFluids=").append(canonFluids(r.fluidOutputs));

        // Chances (optional, but good)
        if (r.outputChances != null && r.outputChances.size() > 0) {
            sb.append("|ch=").append(canonInts(r.outputChances));
            sb.append("|chScale=").append(r.chanceScale != null ? r.chanceScale.intValue() : 0);
        }

        return sb.toString();
    }

    private static String canonItems(List items) {
        if (items == null || items.size() == 0) return "[]";
        List parts = new ArrayList(items.size());
        for (int i = 0; i < items.size(); i++) {
            DumpItemStack s = (DumpItemStack) items.get(i);
            if (s == null) continue;
            String id = nullSafe(s.id);
            int meta = s.meta;
            int cnt = s.count;
            parts.add(id + "@" + meta + "x" + cnt);
        }
        Collections.sort(parts);
        return parts.toString();
    }

    private static String canonFluids(List fluids) {
        if (fluids == null || fluids.size() == 0) return "[]";
        List parts = new ArrayList(fluids.size());
        for (int i = 0; i < fluids.size(); i++) {
            DumpFluidStack f = (DumpFluidStack) fluids.get(i);
            if (f == null) continue;
            parts.add(nullSafe(f.id) + "x" + f.mb);
        }
        Collections.sort(parts);
        return parts.toString();
    }

    private static String canonInts(List ints) {
        if (ints == null || ints.size() == 0) return "[]";
        List parts = new ArrayList(ints.size());
        for (int i = 0; i < ints.size(); i++) {
            Object o = ints.get(i);
            parts.add(String.valueOf(o));
        }
        // Don't sort chances (they correspond to outputs). If you want stable,
        // you can keep as-is OR sort if you also sort outputs and reorder chances.
        return parts.toString();
    }

    private static String nullSafe(String s) {
        return (s == null) ? "" : s;
    }

    private static String sha1Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(b.length * 2);
            for (int i = 0; i < b.length; i++) {
                int v = b[i] & 0xff;
                if (v < 16) out.append('0');
                out.append(Integer.toHexString(v));
            }
            return out.toString();
        } catch (Throwable t) {
            // fallback (shouldn't happen)
            return Integer.toHexString(s.hashCode());
        }
    }

    private static void ensureUniqueRids(List recipes) {
        if (recipes == null || recipes.size() == 0) return;
        Map baseCounts = new HashMap();
        Set used = new HashSet();
        for (int i = 0; i < recipes.size(); i++) {
            DumpRecipe r = (DumpRecipe) recipes.get(i);
            if (r == null || r.rid == null) continue;
            String base = r.rid;
            if (!used.contains(base)) {
                used.add(base);
                baseCounts.put(base, new Integer(1));
                continue;
            }
            int idx = 1;
            Object c = baseCounts.get(base);
            if (c instanceof Integer) idx = ((Integer) c).intValue();
            String candidate = base + ":dup" + idx;
            while (used.contains(candidate)) {
                idx++;
                candidate = base + ":dup" + idx;
            }
            r.rid = candidate;
            used.add(candidate);
            baseCounts.put(base, new Integer(idx + 1));
        }
    }

    private static Boolean isFluidGas(net.minecraftforge.fluids.Fluid f) {
        if (f == null) return null;
        try {
            Method m = f.getClass().getMethod("isGaseous", new Class[0]);
            Object v = m.invoke(f, new Object[0]);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Throwable ignored) {}
        try {
            Method m = f.getClass().getMethod("getGaseous", new Class[0]);
            Object v = m.invoke(f, new Object[0]);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Throwable ignored) {}
        try {
            Field fld = f.getClass().getDeclaredField("isGaseous");
            fld.setAccessible(true);
            Object v = fld.get(f);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Throwable ignored) {}
        try {
            Field fld = f.getClass().getDeclaredField("gaseous");
            fld.setAccessible(true);
            Object v = fld.get(f);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String bestDisplayNameFromBlock(Object mapObj) {
        Object v = getAny(mapObj, new String[] {
                "mMachine", "mMachineBlock", "mMachineItem", "mBlock", "mTileEntity",
                "mMachineStack", "machineStack", "mMachineItemStack", "machineItemStack"
        });
        return displayNameFromObj(v);
    }

    private static String displayNameFromObj(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof ItemStack) {
                return ((ItemStack) v).getDisplayName();
            }
            if (v instanceof Item) {
                return new ItemStack((Item) v).getDisplayName();
            }
            if (v instanceof Block) {
                return new ItemStack((Block) v).getDisplayName();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String bestDisplayNameFromMetaTile(Object mte) {
        return bestDisplayNameFromMetaTile(mte, null);
    }

    private static String bestDisplayNameFromMetaTile(Object mte, String[] sourceOut) {
        if (mte == null) return null;
        Map candidates = collectDisplayNameCandidates(mte);
        return pickBestDisplayName(candidates, sourceOut);
    }

    private static String bestMetaTileName(Object mte) {
        if (mte == null) return null;
        Object v = tryInvokeAny(mte, new String[] {"getMetaName", "getName", "getMachineName"});
        if (v instanceof String) return (String) v;
        Object f = getAny(mte, new String[] {"mName", "name", "mMetaTileEntityName"});
        return (f instanceof String) ? (String) f : null;
    }

    private static Object bestBonusSource(Object mte) {
        if (mte == null) return null;
        Object controller = getAny(mte, new String[] {"mController", "controller", "mMultiBlock", "mMultiblock"});
        if (controller != null) return controller;
        Object v = tryInvokeAny(mte, new String[] {"getController", "getMultiblockController"});
        if (v != null) return v;
        return mte;
    }

    private static boolean isDebugTarget(String className, String metaName, String displayName) {
        String cn = nullSafe(className).toLowerCase();
        String mn = nullSafe(metaName).toLowerCase();
        String dn = nullSafe(displayName).toLowerCase();
        String mnRaw = nullSafe(metaName);
        String dnRaw = nullSafe(displayName);
        if (cn.indexOf("mteindustrialcuttingmachine") >= 0) return true;
        if (cn.indexOf("mteindustrialcompressor") >= 0) return true;
        if (mn.indexOf("industrialcutting") >= 0) return true;
        if (mn.indexOf("compressor") >= 0 && mn.indexOf("industrial") >= 0) return true;
        if (dn.indexOf("industrial cutting") >= 0) return true;
        if (dn.indexOf("large electric compressor") >= 0) return true;
        if (isLikelyInternalName(dnRaw)) return true;
        if (isLikelyInternalName(mnRaw)) return true;
        return false;
    }

    private static Map collectDisplayNameCandidates(Object mte) {
        Map out = new LinkedHashMap();
        if (mte == null) return out;

        addDisplayNameCandidate(out, "metaTileName", bestMetaTileName(mte));

        String[] methodNames = new String[] {
                "getLocalizedName", "getLocalName", "getNameLocalized", "getNameRegional", "getRegionalName",
                "getDisplayName", "getName", "getDescription", "getMachineName", "getMachineType",
                "getInvName", "getInventoryName", "getMetaName", "getUnlocalizedName", "getUnlocalizedNameForUI"
        };
        for (int i = 0; i < methodNames.length; i++) {
            Object v = tryInvokeNoArg(mte, methodNames[i]);
            if (v instanceof String) addDisplayNameCandidate(out, "method:" + methodNames[i], (String) v);
        }

        String[] fieldNames = new String[] {
                "mNameRegional", "mNameLocalized", "mLocalizedName", "mRegionalName", "mDisplayName",
                "mNameLocal", "mLocalName", "mName", "mMetaTileEntityName"
        };
        for (int i = 0; i < fieldNames.length; i++) {
            String v = readFieldString(mte, new String[] { fieldNames[i] });
            addDisplayNameCandidate(out, "field:" + fieldNames[i], v);
        }

        Object stack = tryInvokeAnyWithLong(mte, new String[] {"getStackForm", "getMachineStack", "getItemStack"}, 1L);
        if (stack == null) {
            stack = tryInvokeNoArg(mte, "getStackForm");
        }
        if (stack instanceof net.minecraft.item.ItemStack) {
            net.minecraft.item.ItemStack st = (net.minecraft.item.ItemStack) stack;
            try { addDisplayNameCandidate(out, "stack:displayName", st.getDisplayName()); } catch (Throwable ignored) {}
            try { addDisplayNameCandidate(out, "stack:unlocalizedName", st.getUnlocalizedName()); } catch (Throwable ignored) {}
            try {
                String unloc = st.getUnlocalizedName();
                addLocalizedDisplayNameCandidate(out, "stack:unlocalizedName", unloc);
            } catch (Throwable ignored) {}
        }

        Object unloc = out.get("method:getUnlocalizedName");
        if (unloc instanceof String) addLocalizedDisplayNameCandidate(out, "method:getUnlocalizedName", (String) unloc);
        Object unlocUi = out.get("method:getUnlocalizedNameForUI");
        if (unlocUi instanceof String) addLocalizedDisplayNameCandidate(out, "method:getUnlocalizedNameForUI", (String) unlocUi);

        return out;
    }

    private static void addDisplayNameCandidate(Map out, String key, String value) {
        if (!isUsableName(value)) return;
        if (out.containsKey(key)) return;
        out.put(key, value);
        addLocalizedDisplayNameCandidate(out, key, value);
    }

    private static void addLocalizedDisplayNameCandidate(Map out, String key, String value) {
        String localized = bestLocalizedName(value);
        if (isUsableName(localized)) {
            String locKey = key + ".localized";
            if (!out.containsKey(locKey)) out.put(locKey, localized);
        }
    }

    private static String bestLocalizedName(String key) {
        if (!isUsableName(key)) return null;
        String v = localizedName(key);
        if (isUsableName(v)) return v;
        v = localizedName(key + ".name");
        if (isUsableName(v)) return v;
        if (!key.startsWith("tile.") && !key.startsWith("item.")) {
            v = localizedName("tile." + key);
            if (isUsableName(v)) return v;
            v = localizedName("tile." + key + ".name");
            if (isUsableName(v)) return v;
            v = localizedName("item." + key);
            if (isUsableName(v)) return v;
            v = localizedName("item." + key + ".name");
            if (isUsableName(v)) return v;
        }
        return null;
    }

    private static String pickBestDisplayName(Map candidates, String[] sourceOut) {
        if (candidates == null || candidates.isEmpty()) return null;
        String best = null;
        String bestKey = null;
        int bestScore = Integer.MIN_VALUE;
        Iterator it = candidates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry e = (Map.Entry) it.next();
            Object v = e.getValue();
            if (!(v instanceof String)) continue;
            String s = (String) v;
            if (!isUsableName(s)) continue;
            String key = String.valueOf(e.getKey());
            int score = scoreDisplayName(s, key);
            if (score > bestScore) {
                bestScore = score;
                best = s;
                bestKey = key;
            }
        }
        if (sourceOut != null && sourceOut.length > 0) sourceOut[0] = bestKey;
        return best;
    }

    private static int scoreDisplayName(String s, String key) {
        if (!isUsableName(s)) return Integer.MIN_VALUE;
        int score = 0;
        String v = s.trim();
        if (v.indexOf(' ') >= 0) score += 50;
        if (hasUppercase(v)) score += 10;
        if (isLikelyInternalName(v)) score -= 15;
        if (key != null) {
            if (key.indexOf("mNameRegional") >= 0 || key.indexOf("mNameLocalized") >= 0) score += 15;
            if (key.indexOf("stack:displayName") >= 0) score += 10;
            if (key.indexOf(".localized") >= 0) score += 8;
            if (key.indexOf("getLocalizedName") >= 0) score += 6;
        }
        int lenBonus = v.length() / 4;
        if (lenBonus > 10) lenBonus = 10;
        score += lenBonus;
        return score;
    }

    private static boolean hasUppercase(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') return true;
        }
        return false;
    }

    private static boolean isLikelyInternalName(String s) {
        if (!isUsableName(s)) return false;
        if (s.indexOf(' ') >= 0) return false;
        if (s.indexOf('.') >= 0 || s.indexOf(':') >= 0) return true;
        return s.equals(s.toLowerCase());
    }

    private static boolean shouldReplaceDisplayName(String current, String candidate) {
        if (!isUsableName(candidate)) return false;
        if (!isUsableName(current)) return true;
        if (isLikelyInternalName(current) && !isLikelyInternalName(candidate)) return true;
        return false;
    }

    private static boolean isKeywordMatch(String name) {
        String n = nullSafe(name).toLowerCase();
        for (int i = 0; i < BONUS_DEBUG_KEYWORDS.length; i++) {
            if (n.indexOf(BONUS_DEBUG_KEYWORDS[i]) >= 0) return true;
        }
        return false;
    }

    private static Map collectNumericFields(Object o) {
        if (o == null) return null;
        Map out = new LinkedHashMap();
        Class c = o.getClass();
        while (c != null) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable ignored) {
                c = c.getSuperclass();
                continue;
            }
            for (int i = 0; i < fs.length; i++) {
                Field f = fs[i];
                String name = f.getName();
                if (!isKeywordMatch(name)) continue;
                Class t = f.getType();
                if (!isNumericOrBooleanType(t)) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v != null && isFiniteNumber(v)) out.put(c.getName() + "." + name, v);
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private static Map collectNumericMethods(Object o) {
        if (o == null) return null;
        Map out = new LinkedHashMap();
        Class c = o.getClass();
        while (c != null) {
            Method[] ms;
            try {
                ms = c.getDeclaredMethods();
            } catch (Throwable ignored) {
                c = c.getSuperclass();
                continue;
            }
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                if (m.getParameterTypes().length != 0) continue;
                String name = m.getName();
                if (!isKeywordMatch(name)) continue;
                Class t = m.getReturnType();
                if (!isNumericOrBooleanType(t)) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(o, new Object[0]);
                    if (v != null && isFiniteNumber(v)) out.put(c.getName() + "." + name + "()", v);
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private static boolean isNumericOrBooleanType(Class t) {
        if (t == null) return false;
        if (t.isPrimitive()) {
            return t == Integer.TYPE || t == Long.TYPE || t == Short.TYPE || t == Byte.TYPE
                    || t == Double.TYPE || t == Float.TYPE || t == Boolean.TYPE;
        }
        return Number.class.isAssignableFrom(t) || Boolean.class.isAssignableFrom(t);
    }

    private static boolean isFiniteNumber(Object v) {
        if (v instanceof Double) {
            double d = ((Double) v).doubleValue();
            return !(Double.isNaN(d) || Double.isInfinite(d));
        }
        if (v instanceof Float) {
            float f = ((Float) v).floatValue();
            return !(Float.isNaN(f) || Float.isInfinite(f));
        }
        return true;
    }

    private static boolean isUsableName(String s) {
        return s != null && s.length() > 0;
    }

    private static String localizedName(String key) {
        if (key == null || key.length() == 0) return null;
        try {
            String v = StatCollector.translateToLocal(key);
            if (v != null && !v.equals(key) && v.length() > 0) return v;
        } catch (Throwable ignored) {}
        return null;
    }

    private static void populateMachineBonuses(Object mapObj, DumpRecipeMap map) {
        map.parallelBonus = readNumberFromAny(mapObj, new String[] {
                "getParallelBonus", "getParallelMultiplier", "parallelBonus", "mParallelBonus",
                "parallelMultiplier", "mParallelMultiplier", "mParallelProcessing", "parallelProcessing"
        });
        map.maxParallel = readNumberFromAny(mapObj, new String[] {
                "getMaxParallel", "getParallelLimit", "maxParallel", "mMaxParallel",
                "parallelLimit", "mParallelLimit", "getMaxParallelRecipes", "maxParallelRecipes"
        });
        map.coilBonus = readNumberFromAny(mapObj, new String[] {
                "getCoilBonus", "coilBonus", "mCoilBonus", "coilSpeedBonus", "mCoilSpeedBonus",
                "coilLevel", "mCoilLevel", "getCoilLevel"
        });
        map.speedBonus = readNumberFromAny(mapObj, new String[] {
                "getSpeedBonus", "speedBonus", "mSpeedBonus", "speedMultiplier", "mSpeedMultiplier"
        });
        map.efficiencyBonus = readNumberFromAny(mapObj, new String[] {
                "getEfficiencyBonus", "efficiencyBonus", "mEfficiencyBonus", "getEfficiency",
                "efficiency", "mEfficiency", "getEUtDiscount", "eutDiscount", "mEUtDiscount"
        });
    }

    private static Double readNumberFromAny(Object o, String[] names) {
        Object v = tryInvokeAny(o, names);
        if (v == null) v = getAny(o, names);
        if (v instanceof Number) return new Double(((Number) v).doubleValue());
        return null;
    }

    private static void populateBonusesFromProcessingLogic(Object mte, DumpMachineIndex d) {
        populateBonusesFromProcessingLogic(mte, d, null);
    }

    private static void populateBonusesFromProcessingLogic(Object mte, DumpMachineIndex d, Object logic) {
        if (mte == null || d == null) return;
        if (logic == null) logic = tryCreateProcessingLogic(mte);
        if (logic == null) return;

        Double rawSpeed = readSpeedBonusFromLogic(logic);
        if (rawSpeed != null && rawSpeed.doubleValue() > 0.0) {
            double v = rawSpeed.doubleValue();
            if (v > 0.0 && v < 1.0) {
                d.speedBonus = new Double(1.0 / v);
            } else {
                d.speedBonus = rawSpeed;
            }
        }

        Double euMod = readNumberFromAny(logic, new String[] {
                "getEuModifier", "getEUtModifier", "euModifier", "mEuModifier", "mEUtModifier", "eutModifier"
        });
        if (euMod != null) {
            d.efficiencyBonus = euMod;
        }

        populateBonusesFromOverclockCalculator(logic, d);

        Integer maxPar = readIntSupplierFromAny(logic, new String[] {
                "getMaxParallelSupplier", "maxParallelSupplier", "mMaxParallelSupplier", "getMaxParallel"
        });
        if (maxPar != null && maxPar.intValue() > 0) {
            d.maxParallel = new Double(maxPar.doubleValue());
        }
    }

    private static void populateBonusesFromTooltip(Object mte, DumpMachineIndex d) {
        if (mte == null || d == null) return;
        Object tooltip = tryCreateTooltip(mte);
        if (tooltip == null) return;
        boolean derived = false;

        if (d.parallelBonus == null) {
            Double v = readNumberFromAny(tooltip, new String[] {
                    "getParallel", "getParallelBonus", "getParallels", "getParallelism"
            });
            if (v == null) v = readNumberByKeyword(tooltip, "parallel");
            if (v != null) {
                d.parallelBonus = v;
                derived = true;
            }
        }

        if (d.speedBonus == null) {
            Double v = readNumberFromAny(tooltip, new String[] {
                    "getSpeedBonus", "getSpeedMultiplier", "getSpeedModifier", "getSpeed"
            });
            if (v == null) v = readNumberByKeyword(tooltip, "speed");
            if (v != null) {
                d.speedBonus = v;
                derived = true;
            }
        }

        if (d.efficiencyBonus == null) {
            Double v = readNumberFromAny(tooltip, new String[] {
                    "getEuModifier", "getEUtModifier", "getEnergyModifier", "getEfficiencyBonus", "getEfficiency"
            });
            if (v == null) v = readNumberByKeyword(tooltip, "efficien");
            if (v == null) v = readNumberByKeyword(tooltip, "eumod");
            if (v != null) {
                d.efficiencyBonus = v;
                derived = true;
            }
        }

        List lines = collectTooltipLines(tooltip);
        if (lines != null && lines.size() > 0) {
            if (d.parallelBonus == null) {
                Double v = readParallelFromTooltipLines(lines);
                if (v != null) {
                    d.parallelBonus = v;
                    derived = true;
                }
            }
            if (d.speedBonus == null) {
                Double v = readSpeedFromTooltipLines(lines);
                if (v != null) {
                    d.speedBonus = v;
                    derived = true;
                }
            }
            if (d.efficiencyBonus == null) {
                Double v = readEfficiencyFromTooltipLines(lines);
                if (v != null) {
                    d.efficiencyBonus = v;
                    derived = true;
                }
            }
        }

        if (derived) d.tooltipDerived = Boolean.TRUE;
    }

    private static List collectTooltipLines(Object tooltip) {
        List out = new ArrayList();
        if (tooltip == null) return out;
        Set seen = new LinkedHashSet();

        Object v = tryInvokeNoArg(tooltip, "getTooltip");
        addTooltipLinesFromObj(out, seen, v);
        v = tryInvokeNoArg(tooltip, "getLines");
        addTooltipLinesFromObj(out, seen, v);
        v = tryInvokeNoArg(tooltip, "getInfo");
        addTooltipLinesFromObj(out, seen, v);
        v = tryInvokeNoArg(tooltip, "getInfoLines");
        addTooltipLinesFromObj(out, seen, v);

        Class c = tooltip.getClass();
        while (c != null) {
            Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable ignored) {
                c = c.getSuperclass();
                continue;
            }
            for (int i = 0; i < fs.length; i++) {
                Field f = fs[i];
                try {
                    f.setAccessible(true);
                    Object val = f.get(tooltip);
                    addTooltipLinesFromObj(out, seen, val);
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private static void addTooltipLinesFromObj(List out, Set seen, Object v) {
        if (v == null) return;
        if (v instanceof String) {
            addTooltipLine(out, seen, (String) v);
            return;
        }
        if (v instanceof String[]) {
            String[] arr = (String[]) v;
            for (int i = 0; i < arr.length; i++) addTooltipLine(out, seen, arr[i]);
            return;
        }
        if (v instanceof List) {
            List list = (List) v;
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                if (o instanceof String) {
                    addTooltipLine(out, seen, (String) o);
                }
            }
        }
    }

    private static void addTooltipLine(List out, Set seen, String line) {
        if (!isUsableName(line)) return;
        String cleaned = stripFormatting(line);
        if (!isUsableName(cleaned)) return;
        if (seen.add(cleaned)) out.add(cleaned);
    }

    private static String stripFormatting(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        boolean skip = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (skip) {
                skip = false;
                continue;
            }
            if (c == '\u00A7') {
                skip = true;
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static Double readParallelFromTooltipLines(List lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = ((String) lines.get(i)).toLowerCase();
            if (line.indexOf("parallel") < 0) continue;
            if (line.indexOf("stability") >= 0) continue;
            List tokens = parseNumberTokens(line);
            if (tokens.size() == 0) continue;
            NumberToken t = (NumberToken) tokens.get(0);
            if (t.value > 0.0) return new Double(Math.floor(t.value));
        }
        return null;
    }

    private static Double readSpeedFromTooltipLines(List lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = ((String) lines.get(i)).toLowerCase();
            if (line.indexOf("speed") < 0 && line.indexOf("faster") < 0
                    && line.indexOf("duration") < 0 && line.indexOf("time") < 0) {
                continue;
            }
            List tokens = parseNumberTokens(line);
            if (tokens.size() == 0) continue;
            NumberToken t = (NumberToken) tokens.get(0);
            if (line.indexOf("duration") >= 0 || line.indexOf("time") >= 0) {
                if (t.hasPercent) {
                    double v = t.value / 100.0;
                    if (v > 0.0) return new Double(1.0 / v);
                }
                if (t.value > 0.0) return new Double(1.0 / t.value);
            }
            if (line.indexOf("faster") >= 0 && t.hasPercent) {
                return new Double(1.0 + (t.value / 100.0));
            }
            if (t.hasX) return new Double(t.value);
            if (t.value > 0.0) return new Double(t.value);
        }
        return null;
    }

    private static Double readEfficiencyFromTooltipLines(List lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = ((String) lines.get(i)).toLowerCase();
            if (line.indexOf("efficien") < 0 && line.indexOf("eu") < 0
                    && line.indexOf("energy") < 0 && line.indexOf("usage") < 0 && line.indexOf("steam") < 0) {
                continue;
            }
            List tokens = parseNumberTokens(line);
            if (tokens.size() == 0) continue;
            NumberToken t = (NumberToken) tokens.get(0);
            if (t.hasPercent) return new Double(t.value / 100.0);
            if (t.hasX) return new Double(t.value);
            if (t.value > 0.0 && t.value <= 2.0) return new Double(t.value);
        }
        return null;
    }

    private static final class NumberToken {
        public double value;
        public boolean hasPercent;
        public boolean hasX;
    }

    private static List parseNumberTokens(String line) {
        List out = new ArrayList();
        if (line == null) return out;
        int len = line.length();
        int i = 0;
        while (i < len) {
            char c = line.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                int start = i;
                boolean seenDot = (c == '.');
                i++;
                while (i < len) {
                    char n = line.charAt(i);
                    if (n >= '0' && n <= '9') {
                        i++;
                        continue;
                    }
                    if (n == '.' && !seenDot) {
                        seenDot = true;
                        i++;
                        continue;
                    }
                    break;
                }
                String num = line.substring(start, i);
                try {
                    double val = Double.parseDouble(num);
                    NumberToken t = new NumberToken();
                    t.value = val;
                    t.hasPercent = hasAdjacentMarker(line, start, i, '%');
                    t.hasX = hasAdjacentMarker(line, start, i, 'x') || hasAdjacentMarker(line, start, i, 'X')
                            || hasAdjacentMarker(line, start, i, '\u00D7');
                    out.add(t);
                } catch (Throwable ignored) {}
            } else {
                i++;
            }
        }
        return out;
    }

    private static boolean hasAdjacentMarker(String line, int start, int end, char marker) {
        int i = end;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        if (i < line.length() && line.charAt(i) == marker) return true;
        int j = start - 1;
        while (j >= 0 && line.charAt(j) == ' ') j--;
        return j >= 0 && line.charAt(j) == marker;
    }

    private static Object tryCreateProcessingLogic(Object mte) {
        Method m = findNoArgMethod(mte.getClass(), "createProcessingLogic");
        if (m != null) {
            try {
                m.setAccessible(true);
                return m.invoke(mte, new Object[0]);
            } catch (Throwable ignored) {}
        }
        Object v = tryInvokeAny(mte, new String[] {"getProcessingLogic"});
        if (v != null) return v;
        return null;
    }

    private static Object tryCreateTooltip(Object mte) {
        Method m = findNoArgMethod(mte.getClass(), "createTooltip");
        if (m != null) {
            try {
                m.setAccessible(true);
                return m.invoke(mte, new Object[0]);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String readFieldString(Object o, String[] names) {
        for (int i = 0; i < names.length; i++) {
            Field f = findFieldInHierarchy(o.getClass(), new String[] { names[i] });
            if (f == null) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof String && isUsableName((String) v)) return (String) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object resolveRecipeMap(Object mte) {
        Object mapObj = tryInvokeAny(mte, new String[] {"getRecipeMap", "getRecipeMapForNEI", "getRecipeMapNEI"});
        if (mapObj != null) return mapObj;
        mapObj = tryInvokeAnyDeclared(mte, new String[] {"getRecipeMap", "getRecipeMapForNEI", "getRecipeMapNEI"});
        if (mapObj != null) return mapObj;
        mapObj = getAny(mte, new String[] {"mRecipeMap", "recipeMap", "mMap"});
        return mapObj;
    }

    private static boolean hasAvailableRecipeMaps(Object mte) {
        Object maps = tryInvokeAny(mte, new String[] {"getAvailableRecipeMaps", "getAvailableRecipeMap"});
        if (maps == null) maps = tryInvokeAnyDeclared(mte, new String[] {"getAvailableRecipeMaps", "getAvailableRecipeMap"});
        if (maps instanceof Collection) {
            return !((Collection) maps).isEmpty();
        }
        return false;
    }

    private static Object tryInvokeAnyDeclared(Object o, String[] names) {
        for (int i = 0; i < names.length; i++) {
            Method m = findNoArgMethod(o.getClass(), names[i]);
            if (m == null) continue;
            try {
                m.setAccessible(true);
                return m.invoke(o, new Object[0]);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object tryInvokeAnyWithLong(Object o, String[] names, long value) {
        for (int i = 0; i < names.length; i++) {
            try {
                Method m = findOneArgMethod(o.getClass(), names[i], Long.TYPE);
                if (m != null) return m.invoke(o, new Object[] { new Long(value) });
                m = findOneArgMethod(o.getClass(), names[i], Long.class);
                if (m != null) return m.invoke(o, new Object[] { new Long(value) });
                m = findOneArgMethod(o.getClass(), names[i], Integer.TYPE);
                if (m != null) return m.invoke(o, new Object[] { new Integer((int) value) });
                m = findOneArgMethod(o.getClass(), names[i], Integer.class);
                if (m != null) return m.invoke(o, new Object[] { new Integer((int) value) });
                m = findOneArgMethod(o.getClass(), names[i], Short.TYPE);
                if (m != null) return m.invoke(o, new Object[] { new Short((short) value) });
                m = findOneArgMethod(o.getClass(), names[i], Short.class);
                if (m != null) return m.invoke(o, new Object[] { new Short((short) value) });
                m = findOneArgMethod(o.getClass(), names[i], Byte.TYPE);
                if (m != null) return m.invoke(o, new Object[] { new Byte((byte) value) });
                m = findOneArgMethod(o.getClass(), names[i], Byte.class);
                if (m != null) return m.invoke(o, new Object[] { new Byte((byte) value) });
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method findOneArgMethod(Class c, String name, Class paramType) {
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, new Class[] { paramType });
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static Method findNoArgMethod(Class c, String name) {
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, new Class[0]);
                return m;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static Integer readIntSupplierFromAny(Object o, String[] names) {
        Object v = tryInvokeAny(o, names);
        if (v == null) v = getAny(o, names);
        if (v == null) return null;
        if (v instanceof Number) return new Integer(((Number) v).intValue());
        try {
            Method m = v.getClass().getMethod("getAsInt", new Class[0]);
            m.setAccessible(true);
            Object out = m.invoke(v, new Object[0]);
            if (out instanceof Number) return new Integer(((Number) out).intValue());
        } catch (Throwable ignored) {}
        try {
            Method m = v.getClass().getMethod("call", new Class[0]);
            m.setAccessible(true);
            Object out = m.invoke(v, new Object[0]);
            if (out instanceof Number) return new Integer(((Number) out).intValue());
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer computeParallelPerTier(Object mte) {
        if (mte == null) return null;
        Method m = findNoArgMethod(mte.getClass(), "getMaxParallelRecipes");
        if (m == null) return null;

        Integer tier = getTierForVoltage(32L);
        if (tier == null || tier.intValue() <= 0) return null;

        Field f = findFieldInHierarchy(mte.getClass(), new String[] {
                "mMaxInputVoltage", "maxInputVoltage", "mMaxVoltage", "mInputVoltage", "mVoltage"
        });
        Object prior = null;
        boolean set = false;
        if (f != null) {
            try {
                f.setAccessible(true);
                prior = f.get(mte);
                if (f.getType() == Long.TYPE || f.getType() == Long.class) {
                    f.set(mte, new Long(32L));
                    set = true;
                } else if (f.getType() == Integer.TYPE || f.getType() == Integer.class) {
                    f.set(mte, new Integer(32));
                    set = true;
                } else if (f.getType() == Short.TYPE || f.getType() == Short.class) {
                    f.set(mte, new Short((short) 32));
                    set = true;
                } else if (f.getType() == Byte.TYPE || f.getType() == Byte.class) {
                    f.set(mte, new Byte((byte) 32));
                    set = true;
                }
            } catch (Throwable ignored) {}
        }

        Integer max = null;
        try {
            m.setAccessible(true);
            Object v = m.invoke(mte, new Object[0]);
            if (v instanceof Number) max = new Integer(((Number) v).intValue());
        } catch (Throwable ignored) {}

        if (set && f != null) {
            try { f.set(mte, prior); } catch (Throwable ignored) {}
        }

        if (max != null && tier.intValue() > 0) {
            return new Integer(max.intValue() / tier.intValue());
        }
        return null;
    }

    private static Double readSpeedBonusFromLogic(Object logic) {
        Double rawSpeed = readNumberFromAny(logic, new String[] {
                "getSpeedBonus", "getSpeedModifier", "getSpeedMultiplier",
                "speedBonus", "mSpeedBonus", "speedModifier", "mSpeedModifier",
                "speedMultiplier", "mSpeedMultiplier", "mSpeed"
        });
        if (rawSpeed != null) return rawSpeed;

        Double byField = readNumberByKeyword(logic, "speed");
        if (byField != null) return byField;

        Double duration = readDurationModifier(logic);
        if (duration != null && duration.doubleValue() > 0.0) {
            return new Double(1.0 / duration.doubleValue());
        }
        return null;
    }

    private static void populateBonusesFromOverclockCalculator(Object logic, DumpMachineIndex d) {
        if (logic == null || d == null) return;
        Object calc = findOverclockCalculator(logic);
        if (calc == null) return;

        if (d.speedBonus == null) {
            Double duration = readDurationModifier(calc);
            if (duration != null && duration.doubleValue() > 0.0) {
                d.speedBonus = new Double(1.0 / duration.doubleValue());
            }
        }

        if (d.efficiencyBonus == null) {
            Double euMod = readNumberFromAny(calc, new String[] {
                    "getEUtDiscount", "getEuDiscount", "getEUtModifier", "getEuModifier",
                    "eutDiscount", "euDiscount", "mEUtDiscount", "mEuDiscount",
                    "euModifier", "mEuModifier", "eutModifier", "mEUtModifier"
            });
            if (euMod == null) euMod = readNumberByKeyword(calc, "discount");
            if (euMod == null) euMod = readNumberByKeyword(calc, "eut");
            if (euMod == null) euMod = readNumberByKeyword(calc, "energy");
            if (euMod != null) d.efficiencyBonus = euMod;
        }
    }

    private static Object findOverclockCalculator(Object logic) {
        Object v = getAny(logic, new String[] {
                "mOverclockCalculator", "overclockCalculator", "mOverclockCalc", "overclockCalc",
                "mCalculator", "calculator"
        });
        if (v != null) return v;
        Class c = logic.getClass();
        while (c != null) {
            Field[] fs = c.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                Field f = fs[i];
                Class t = f.getType();
                String tn = (t == null) ? "" : t.getName().toLowerCase();
                if (tn.indexOf("overclock") < 0) continue;
                try {
                    f.setAccessible(true);
                    Object out = f.get(logic);
                    if (out != null) return out;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Double readDurationModifier(Object o) {
        if (o == null) return null;
        Double v = readNumberFromAny(o, new String[] {
                "getDurationModifier", "durationModifier", "mDurationModifier",
                "getDurationMultiplier", "durationMultiplier", "mDurationMultiplier",
                "getTimeModifier", "timeModifier", "mTimeModifier"
        });
        if (v != null) return v;
        v = readNumberByKeyword(o, "duration");
        if (v != null) return v;
        v = readNumberByKeyword(o, "time");
        if (v != null) return v;
        return null;
    }

    private static Double readNumberByKeyword(Object o, String keyword) {
        if (o == null) return null;
        String kw = nullSafe(keyword).toLowerCase();
        Double found = null;
        int hits = 0;
        Class c = o.getClass();
        while (c != null) {
            Field[] fs = c.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                Field f = fs[i];
                String name = f.getName();
                if (name == null || name.toLowerCase().indexOf(kw) < 0) continue;
                Class t = f.getType();
                if (!isNumericOrBooleanType(t)) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof Number) {
                        found = new Double(((Number) v).doubleValue());
                        hits++;
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return (hits == 1) ? found : null;
    }

    private static void sanitizeBonusFields(DumpMachineIndex d) {
        if (d == null) return;
        if (d.parallelBonus != null && !isFiniteNumber(d.parallelBonus)) d.parallelBonus = null;
        if (d.maxParallel != null && !isFiniteNumber(d.maxParallel)) d.maxParallel = null;
        if (d.coilBonus != null && !isFiniteNumber(d.coilBonus)) d.coilBonus = null;
        if (d.speedBonus != null && !isFiniteNumber(d.speedBonus)) d.speedBonus = null;
        if (d.efficiencyBonus != null && !isFiniteNumber(d.efficiencyBonus)) d.efficiencyBonus = null;
        if (d.parallelBonus != null && d.parallelBonus.doubleValue() <= 0.0) d.parallelBonus = null;
        if (d.maxParallel != null && d.maxParallel.doubleValue() <= 0.0) d.maxParallel = null;
        if (d.coilBonus != null && d.coilBonus.doubleValue() <= 0.0) d.coilBonus = null;
        if (d.speedBonus != null && d.speedBonus.doubleValue() <= 0.0) d.speedBonus = null;
        if (d.efficiencyBonus != null && d.efficiencyBonus.doubleValue() <= 0.0) d.efficiencyBonus = null;
    }

    private static void roundBonusFields(DumpMachineIndex d) {
        if (d == null) return;
        d.parallelBonus = round2(d.parallelBonus);
        d.maxParallel = round2(d.maxParallel);
        d.coilBonus = round2(d.coilBonus);
        d.speedBonus = round2(d.speedBonus);
        d.efficiencyBonus = round2(d.efficiencyBonus);
    }

    private static Double round2(Double v) {
        if (v == null) return null;
        double d = v.doubleValue();
        double r = Math.round(d * 100.0) / 100.0;
        return new Double(r);
    }

    private static Integer getTierForVoltage(long voltage) {
        try {
            Class c = Class.forName("gregtech.api.util.GT_Utility");
            Method m = c.getMethod("getTier", new Class[] { long.class });
            Object v = m.invoke(null, new Object[] { new Long(voltage) });
            if (v instanceof Number) return new Integer(((Number) v).intValue());
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findFieldInHierarchy(Class c, String[] names) {
        while (c != null) {
            for (int i = 0; i < names.length; i++) {
                try {
                    Field f = c.getDeclaredField(names[i]);
                    return f;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static void addPowerDerivedFields(DumpRecipe r) {
        int eut = r.eut;
        if (eut <= 0) return;

        Tier t = minTierForEUt(eut);
        if (t == null) return;

        r.minTier = t.name;
        r.minVoltage = new Integer(t.voltage);
        r.ampsAtMinTier = new Integer((eut + t.voltage - 1) / t.voltage); // ceil
    }

    private static final class Tier {
        final String name;
        final int voltage;
        Tier(String name, int voltage) { this.name = name; this.voltage = voltage; }
    }

    private static Tier minTierForEUt(int eut) {
        // Classic GT tiers (amps separate); adjust if your GTNH uses different caps.
        Tier[] tiers = new Tier[] {
                new Tier("ULV", 8),
                new Tier("LV", 32),
                new Tier("MV", 128),
                new Tier("HV", 512),
                new Tier("EV", 2048),
                new Tier("IV", 8192),
                new Tier("LuV", 32768),
                new Tier("ZPM", 131072),
                new Tier("UV", 524288),
                new Tier("UHV", 2097152),
        };
        for (int i = 0; i < tiers.length; i++) {
            if (eut <= tiers[i].voltage) return tiers[i];
        }
        return tiers[tiers.length - 1];
    }

    /* ---------- JSON root + data classes ---------- */

    public static final class DumpRoot {
        public String generatedAt;
        public String minecraft;
        public String mod;
        public List recipeMaps; // List<DumpRecipeMap>
    }

    public static final class DumpRecipeMap {
        public String machineId;
        public String displayName;
        public String declaringField;
        public int recipeCount;
        public Double parallelBonus;
        public Double maxParallel;
        public Double coilBonus;
        public Double speedBonus;
        public Double efficiencyBonus;
        public Boolean tooltipDerived;
        public List recipes; // List<DumpRecipe>
    }

    public static final class DumpMachineIndex {
        public String machineId;
        public String displayName;
        public Integer metaTileId;
        public String metaTileName;
        public String metaTileClass;
        public Double parallelBonus;
        public Double maxParallel;
        public Double coilBonus;
        public Double speedBonus;
        public Double efficiencyBonus;
        public Boolean tooltipDerived;
    }

    public static final class DumpMachineIndexDebug {
        public String machineId;
        public String displayName;
        public String displayNameSource;
        public Integer metaTileId;
        public String metaTileName;
        public String metaTileClass;
        public String controllerClass;
        public Map metaTileFields;
        public Map metaTileMethods;
        public Map controllerFields;
        public Map controllerMethods;
        public Map displayNameCandidates;
    }

    public static final class DumpMachineIndexRoot {
        public String generatedAt;
        public String minecraft;
        public String mod;
        public List machineIndex; // List<DumpMachineIndex>
    }

    public static final class DumpMachineIndexDebugRoot {
        public String generatedAt;
        public String minecraft;
        public String mod;
        public List machineIndexDebug; // List<DumpMachineIndexDebug>
    }

    public static final class DumpRecipe {
        public String rid;
        public String machineId;
        public String recipeClass;
        public String minTier;    // "ULV", "LV", etc.
        public int durationTicks;
        public int eut;
        public Integer specialValue;  // raw GT "special" (EBF temp, etc)
        public Integer ebfTemp;       // if applicable (blast furnace)
        public Integer circuitConfig; // ghost circuit meta
        public Integer minVoltage;    // numeric voltage cap for that tier
        public Integer ampsAtMinTier; // ceil (eut/minVoltage)
        public Boolean circuitGhost;
        public List itemInputs;   // List<DumpItemStack>
        public List itemOutputs;  // List<DumpItemStack>
        public List fluidInputs;  // List<DumpFluidStack>
        public List fluidOutputs; // List<DumpFluidStack>
        public List outputChances; // List<Integer>
        public Integer chanceScale;
    }

    public static final class DumpItemStack {
        public String id;
        public int count;
        public int meta;
        public Double chance;
        public String displayName;
        public String unlocalizedName;
        public List oreDict; // List<String>
    }

    public static final class DumpFluidStack {
        public String id;
        public int mb;
        public Boolean isGas;
        public String displayName;
        public String unlocalizedName;
    }
}
