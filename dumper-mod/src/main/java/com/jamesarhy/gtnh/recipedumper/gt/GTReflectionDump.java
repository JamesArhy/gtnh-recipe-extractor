package com.jamie.gtnh.recipedumper.gt;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

public final class GTReflectionDump {

    // Try likely classnames. You can add more as you observe them in logs.
    private static final String[] RECIPE_MAPS_CANDIDATES = new String[] {
            "gregtech.api.recipe.RecipeMaps"
    };

    public static List<DumpRecipeMap> dumpAllRecipeMaps() {
        Class<?> mapsClass = loadFirst(RECIPE_MAPS_CANDIDATES);
        if (mapsClass == null) {
            System.out.println("[recipedumper] Could not find gregtech RecipeMaps class. Is GregTech loaded?");
            return Collections.emptyList();
        }

        List<DumpRecipeMap> out = new ArrayList<>();

        for (Field f : mapsClass.getDeclaredFields()) {
            try {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);

                Object mapObj = f.get(null);
                if (mapObj == null) continue;

                String cn = mapObj.getClass().getName();
                if (!cn.contains("RecipeMap") && !cn.contains("GT_Recipe_Map")) continue;

                DumpRecipeMap m = new DumpRecipeMap();
                m.declaringField = mapsClass.getName() + "." + f.getName();
                m.machineId = bestMachineId(mapObj, f.getName());
                m.displayName = bestDisplayName(mapObj, f.getName());

                Collection<?> recipes = getRecipesFromMap(mapObj);
                m.recipeCount = recipes.size();
                m.recipes = new ArrayList<>(recipes.size());

                int i = 0;
                for (Object rObj : recipes) {
                    DumpRecipe r = dumpRecipe(rObj, m.machineId);
                    if (r != null) m.recipes.add(r);
                    if (++i % 5000 == 0) {
                        System.out.println("[recipedumper] " + m.machineId + " dumped " + i + " recipes...");
                    }
                }

                out.add(m);
            } catch (Throwable t) {
                System.out.println("[recipedumper] Failed map field: " + f.getName() + " err=" + t);
            }
        }

        // Sort maps for stable output
        out.sort(Comparator.comparing(a -> a.machineId == null ? "" : a.machineId));
        return out;
    }

    private static DumpRecipe dumpRecipe(Object rObj, String machineId) {
        try {
            DumpRecipe r = new DumpRecipe();
            r.machineId = machineId;
            r.recipeClass = rObj.getClass().getName();

            r.durationTicks = asInt(getAny(rObj, "mDuration", "duration", "durationTicks"));
            r.eut = asInt(getAny(rObj, "mEUt", "EUt", "eut", "mEU"));

            r.itemInputs = dumpItemStacks(getAny(rObj, "mInputs", "inputs", "mInput"));
            r.itemOutputs = dumpItemStacks(getAny(rObj, "mOutputs", "outputs", "mOutput"));

            r.fluidInputs = dumpFluids(getAny(rObj, "mFluidInputs", "fluidInputs", "mFluidInput"));
            r.fluidOutputs = dumpFluids(getAny(rObj, "mFluidOutputs", "fluidOutputs", "mFluidOutput"));

            int[] chances = asIntArray(getAny(rObj, "mChances", "outputChances", "chances"));
            if (chances != null) {
                r.outputChances = toList(chances);
                r.chanceScale = guessChanceScale(chances);
            }

            r.rid = stableRid(machineId, r);

            return r;
        } catch (Throwable t) {
            // Don’t kill the run if a single recipe fails
            return null;
        }
    }

    // ---------- Map identity (NEI-like “tab name”) ----------

    private static String bestMachineId(Object mapObj, String fallbackFieldName) {
        Object id = tryInvokeAny(mapObj, "getUnlocalizedName", "getName", "getID");
        if (id instanceof String && !((String) id).trim().isEmpty()) return (String) id;

        Object nameField = getAny(mapObj, "mUnlocalizedName", "mName", "mNEIName", "unlocalizedName", "name");
        if (nameField instanceof String && !((String) nameField).trim().isEmpty()) return (String) nameField;

        return "gt.map." + fallbackFieldName;
    }

    private static String bestDisplayName(Object mapObj, String fallbackFieldName) {
        Object dn = tryInvokeAny(mapObj, "getLocalizedName", "getDisplayName");
        if (dn instanceof String && !((String) dn).trim().isEmpty()) return (String) dn;

        Object nameField = getAny(mapObj, "mNEIName", "mName", "displayName", "name");
        if (nameField instanceof String && !((String) nameField).trim().isEmpty()) return (String) nameField;

        return fallbackFieldName;
    }

    // ---------- Recipe list extraction ----------

    private static Collection<?> getRecipesFromMap(Object mapObj) {
        Object viaMethod = tryInvokeAny(mapObj, "getAllRecipes", "getRecipes", "getRecipeList", "values");
        if (viaMethod instanceof Map) return ((Map<?, ?>) viaMethod).values();
        if (viaMethod instanceof Collection) return (Collection<?>) viaMethod;

        // Field fallback: pick first Map/Collection that looks plausible
        for (Field f : mapObj.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object v = f.get(mapObj);
                if (v instanceof Map) return ((Map<?, ?>) v).values();
                if (v instanceof Collection) return (Collection<?>) v;
            } catch (Throwable ignored) {}
        }
        return Collections.emptyList();
    }

    // ---------- Stack dumping ----------

    private static List<DumpItemStack> dumpItemStacks(Object v) {
        List<DumpItemStack> out = new ArrayList<>();
        if (v == null) return out;

        if (v instanceof ItemStack[]) {
            for (ItemStack st : (ItemStack[]) v) addItem(out, st);
            return out;
        }
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof ItemStack) addItem(out, (ItemStack) o);
                // If GT uses “alternative ingredient lists”, you can extend here later.
            }
            return out;
        }
        if (v instanceof ItemStack) {
            addItem(out, (ItemStack) v);
        }
        return out;
    }

    private static void addItem(List<DumpItemStack> out, ItemStack st) {
        if (st == null) return;
        DumpItemStack d = new DumpItemStack();
        d.id = itemKey(st);
        d.count = st.stackSize;
        d.meta = st.getItemDamage();
        // NBT intentionally omitted for now; you can add hash later if needed.
        out.add(d);
    }

    private static List<DumpFluidStack> dumpFluids(Object v) {
        List<DumpFluidStack> out = new ArrayList<>();
        if (v == null) return out;

        if (v instanceof FluidStack[]) {
            for (FluidStack fs : (FluidStack[]) v) addFluid(out, fs);
            return out;
        }
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof FluidStack) addFluid(out, (FluidStack) o);
            }
            return out;
        }
        if (v instanceof FluidStack) {
            addFluid(out, (FluidStack) v);
        }
        return out;
    }

    private static void addFluid(List<DumpFluidStack> out, FluidStack fs) {
        if (fs == null || fs.getFluid() == null) return;
        DumpFluidStack d = new DumpFluidStack();
        d.id = "fluid:" + fs.getFluid().getName();
        d.mb = fs.amount;
        out.add(d);
    }

    private static String itemKey(ItemStack st) {
        try {
            String name = net.minecraft.item.Item.itemRegistry.getNameForObject(st.getItem());
            if (name == null) name = "unknown";
            return "item:" + name;
        } catch (Throwable t) {
            return "item:unknown";
        }
    }

    // ---------- Reflection helpers ----------

    private static Class<?> loadFirst(String[] candidates) {
        for (String cn : candidates) {
            try {
                return Class.forName(cn);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object tryInvokeAny(Object target, String... names) {
        for (String n : names) {
            try {
                Method m = target.getClass().getMethod(n);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object getAny(Object target, String... fieldNames) {
        for (String n : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(n);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {}
        }
        // Walk superclasses too
        Class<?> c = target.getClass().getSuperclass();
        while (c != null && c != Object.class) {
            for (String n : fieldNames) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static int asInt(Object v) {
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Short) return ((Short) v).intValue();
        if (v instanceof Long) return ((Long) v).intValue();
        if (v instanceof Byte) return ((Byte) v).intValue();
        return 0;
    }

    private static int[] asIntArray(Object v) {
        if (v instanceof int[]) return (int[]) v;
        return null;
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> out = new ArrayList<>(a.length);
        for (int x : a) out.add(x);
        return out;
    }

    private static Integer guessChanceScale(int[] chances) {
        // Heuristic: if values look like 0..10000, use 10000. Otherwise null.
        int max = 0;
        for (int c : chances) if (c > max) max = c;
        if (max <= 10000) return 10000;
        if (max <= 100000) return 100000;
        return null;
    }

    private static String stableRid(String machineId, DumpRecipe r) {
        // Hash a stable string: machine + duration + eut + stacks
        StringBuilder sb = new StringBuilder();
        sb.append(machineId).append("|").append(r.durationTicks).append("|").append(r.eut).append("|");
        for (DumpItemStack s : r.itemInputs) sb.append("in:").append(s.id).append(":").append(s.meta).append(":").append(s.count).append(";");
        for (DumpFluidStack s : r.fluidInputs) sb.append("fin:").append(s.id).append(":").append(s.mb).append(";");
        for (DumpItemStack s : r.itemOutputs) sb.append("out:").append(s.id).append(":").append(s.meta).append(":").append(s.count).append(";");
        for (DumpFluidStack s : r.fluidOutputs) sb.append("fout:").append(s.id).append(":").append(s.mb).append(";");

        return "gt:" + machineId + ":" + sha1(sb.toString()).substring(0, 12);
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (byte x : b) out.append(String.format("%02x", x));
            return out.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    // ---------- JSON structs ----------

    public static final class DumpRoot {
        public String generatedAt;
        public String minecraft;
        public String mod;
        public List<DumpRecipeMap> recipeMaps;
    }

    public static final class DumpRecipeMap {
        public String machineId;
        public String displayName;
        public String declaringField;
        public int recipeCount;
        public List<DumpRecipe> recipes;
    }

    public static final class DumpRecipe {
        public String rid;
        public String machineId;
        public String recipeClass;
        public int durationTicks;
        public int eut;
        public List<DumpItemStack> itemInputs = new ArrayList<>();
        public List<DumpFluidStack> fluidInputs = new ArrayList<>();
        public List<DumpItemStack> itemOutputs = new ArrayList<>();
        public List<DumpFluidStack> fluidOutputs = new ArrayList<>();
        public List<Integer> outputChances;
        public Integer chanceScale;
    }

    public static final class DumpItemStack {
        public String id;   // "item:modid:name"
        public int count;
        public int meta;
    }

    public static final class DumpFluidStack {
        public String id;   // "fluid:name"
        public int mb;
    }
}
