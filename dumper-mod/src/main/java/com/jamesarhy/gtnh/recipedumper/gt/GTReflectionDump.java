package com.jamesarhy.gtnh.recipedumper.gt;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.lang.reflect.*;
import java.util.*;

public final class GTReflectionDump {

    private static final String[] RECIPE_MAPS_CANDIDATES = new String[] {
            "gregtech.api.recipe.RecipeMaps"
    };

    public static List<DumpRecipeMap> dumpAllRecipeMaps() {
        Class mapsClass = loadFirst(RECIPE_MAPS_CANDIDATES);
        if (mapsClass == null) {
            System.out.println("[recipedumper] GregTech RecipeMaps not found");
            return Collections.emptyList();
        }

        List out = new ArrayList(); // List<DumpRecipeMap>

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
                map.displayName = bestDisplayName(mapObj, f.getName());

                Collection recipes = getRecipesFromMap(mapObj);
                map.recipeCount = recipes.size();
                map.recipes = new ArrayList(); // List<DumpRecipe>

                Iterator it = recipes.iterator();
                while (it.hasNext()) {
                    Object rObj = it.next();
                    DumpRecipe r = dumpRecipe(rObj, map.machineId);
                    if (r != null) map.recipes.add(r);
                }

                out.add(map);

            } catch (Throwable t) {
                System.out.println("[recipedumper] Failed map " + f.getName() + ": " + t);
            }
        }

        Collections.sort(out, new Comparator() {
            public int compare(Object oa, Object ob) {
                DumpRecipeMap a = (DumpRecipeMap) oa;
                DumpRecipeMap b = (DumpRecipeMap) ob;
                String am = (a == null || a.machineId == null) ? "" : a.machineId;
                String bm = (b == null || b.machineId == null) ? "" : b.machineId;
                return am.compareTo(bm);
            }
        });

        //noinspection unchecked
        return (List<DumpRecipeMap>) out;
    }

    private static DumpRecipe dumpRecipe(Object rObj, String machineId) {
        try {
            DumpRecipe r = new DumpRecipe();
            r.machineId = machineId;
            r.recipeClass = rObj.getClass().getName();

            r.durationTicks = asInt(getAny(rObj, new String[] {"mDuration", "duration", "durationTicks"}));
            r.eut = asInt(getAny(rObj, new String[] {"mEUt", "EUt", "eut", "mEU"}));

            r.itemInputs = dumpItemStacks(getAny(rObj, new String[] {"mInputs", "inputs", "mInput"}));
            r.itemOutputs = dumpItemStacks(getAny(rObj, new String[] {"mOutputs", "outputs", "mOutput"}));

            r.fluidInputs = dumpFluids(getAny(rObj, new String[] {"mFluidInputs", "fluidInputs", "mFluidInput"}));
            r.fluidOutputs = dumpFluids(getAny(rObj, new String[] {"mFluidOutputs", "fluidOutputs", "mFluidOutput"}));

            int[] chances = asIntArray(getAny(rObj, new String[] {"mChances", "chances", "outputChances"}));
            if (chances != null) {
                r.outputChances = toIntList(chances);
                r.chanceScale = guessChanceScale(chances);
            }

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

    private static String bestDisplayName(Object mapObj, String fallbackFieldName) {
        Object s = tryInvokeAny(mapObj, new String[] {"getLocalizedName", "getDisplayName"});
        if (s instanceof String && ((String) s).length() > 0) return (String) s;

        Object f = getAny(mapObj, new String[] {"mNEIName", "mName", "displayName", "name"});
        if (f instanceof String && ((String) f).length() > 0) return (String) f;

        return fallbackFieldName;
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
        out.add(d);
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
        out.add(d);
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

    private static String stableRid(String machineId, DumpRecipe r) {
        // Simple stable id. You can swap this to a real hash later.
        String base = machineId + "|" + r.durationTicks + "|" + r.eut + "|" + r.recipeClass;
        return "gt:" + machineId + ":" + Integer.toHexString(base.hashCode());
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
        public List recipes; // List<DumpRecipe>
    }

    public static final class DumpRecipe {
        public String rid;
        public String machineId;
        public String recipeClass;
        public int durationTicks;
        public int eut;
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
    }

    public static final class DumpFluidStack {
        public String id;
        public int mb;
    }
}
