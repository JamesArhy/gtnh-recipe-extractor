package com.jamesarhy.gtnh.recipedumper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jamesarhy.gtnh.recipedumper.gt.GTReflectionDump;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;

import net.minecraft.server.MinecraftServer;

import java.io.File;
import java.io.FileWriter;
import java.util.Date;

@Mod(
        modid = RecipeDumperMod.MODID,
        name = "Recipe Dumper",
        version = "0.1.0",
        acceptableRemoteVersions = "*"
)
public class RecipeDumperMod {
    public static final String MODID = "recipedumper";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private File outFile;
    private File machineIndexFile;
    private File machineIndexDebugFile;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        File dir = new File(e.getModConfigurationDirectory(), MODID);
        if (!dir.exists()) dir.mkdirs();
        outFile = new File(dir, "recipes.json");
        machineIndexFile = new File(dir, "machine_index.json");
        machineIndexDebugFile = new File(dir, "machine_index_debug.json");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        // no-op (do not register on EventBus)
    }

    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent e) {
        boolean hasRecipes = outFile.exists() && outFile.length() > 0;
        boolean hasMachineIndex = machineIndexFile.exists() && machineIndexFile.length() > 0;
        boolean hasMachineIndexDebug = machineIndexDebugFile.exists() && machineIndexDebugFile.length() > 0;
        boolean skipExisting = "true".equalsIgnoreCase(System.getenv("RECIPE_DUMP_SKIP_EXISTING"));
        if (skipExisting && hasRecipes && hasMachineIndex && hasMachineIndexDebug) {
            System.out.println("[" + MODID + "] recipes.json, machine_index.json, and machine_index_debug.json already exist; skipping");
            return;
        }

        try {
            GTReflectionDump.DumpRoot root = new GTReflectionDump.DumpRoot();
            root.generatedAt = new Date().toString();
            root.minecraft = "1.7.10";
            root.mod = MODID;
            root.recipeMaps = GTReflectionDump.dumpAllRecipeMapsWithProviders();

            GTReflectionDump.DumpMachineIndexRoot miRoot = new GTReflectionDump.DumpMachineIndexRoot();
            miRoot.generatedAt = root.generatedAt;
            miRoot.minecraft = root.minecraft;
            miRoot.mod = root.mod;
            java.util.List metaTiles = GTReflectionDump.dumpMachineIndexFromMetaTiles();
            try {
                java.util.List railcraft = GTReflectionDump.dumpMachineIndexFromRailcraftAlpha();
                if (railcraft != null && railcraft.size() > 0) metaTiles.addAll(railcraft);
            } catch (Throwable t) {
                t.printStackTrace();
            }
            miRoot.machineIndex = GTReflectionDump.mergeMachineIndexWithRecipeMaps(
                metaTiles,
                GTReflectionDump.dumpMachineIndexFromRecipeMaps(root.recipeMaps));

            GTReflectionDump.DumpMachineIndexDebugRoot midRoot = new GTReflectionDump.DumpMachineIndexDebugRoot();
            midRoot.generatedAt = root.generatedAt;
            midRoot.minecraft = root.minecraft;
            midRoot.mod = root.mod;
            try {
                midRoot.machineIndexDebug = GTReflectionDump.dumpMachineIndexDebugFromMetaTiles();
            } catch (Throwable t) {
                t.printStackTrace();
                midRoot.machineIndexDebug = new java.util.ArrayList();
            }

            File tmp = new File(outFile.getAbsolutePath() + ".tmp");
            File tmpMachine = new File(machineIndexFile.getAbsolutePath() + ".tmp");
            File tmpMachineDebug = new File(machineIndexDebugFile.getAbsolutePath() + ".tmp");
            FileWriter fw = null;
            try {
                fw = new FileWriter(tmp);
                fw.write(GSON.toJson(root));
            } finally {
                if (fw != null) {
                    try { fw.close(); } catch (Exception ignored) {}
                }
            }

            if (outFile.exists()) outFile.delete();
            tmp.renameTo(outFile);

            fw = null;
            try {
                fw = new FileWriter(tmpMachine);
                fw.write(GSON.toJson(miRoot));
            } finally {
                if (fw != null) {
                    try { fw.close(); } catch (Exception ignored) {}
                }
            }

            if (machineIndexFile.exists()) machineIndexFile.delete();
            tmpMachine.renameTo(machineIndexFile);

            fw = null;
            try {
                fw = new FileWriter(tmpMachineDebug);
                fw.write(GSON.toJson(midRoot));
            } finally {
                if (fw != null) {
                    try { fw.close(); } catch (Exception ignored) {}
                }
            }

            if (machineIndexDebugFile.exists()) machineIndexDebugFile.delete();
            tmpMachineDebug.renameTo(machineIndexDebugFile);

            System.out.println("[" + MODID + "] wrote " + outFile.getAbsolutePath());
            System.out.println("[" + MODID + "] wrote " + machineIndexFile.getAbsolutePath());
            System.out.println("[" + MODID + "] wrote " + machineIndexDebugFile.getAbsolutePath());

            // Shut down server so CI/docker can finish.
            MinecraftServer srv = MinecraftServer.getServer();
            if (srv != null) {
                srv.initiateShutdown();
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
