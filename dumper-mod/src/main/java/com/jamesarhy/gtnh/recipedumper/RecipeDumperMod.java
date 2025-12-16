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

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        File dir = new File(e.getModConfigurationDirectory(), MODID);
        if (!dir.exists()) dir.mkdirs();
        outFile = new File(dir, "recipes.json");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        // no-op (do not register on EventBus)
    }

    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent e) {
        if (outFile.exists() && outFile.length() > 0) {
            System.out.println("[" + MODID + "] recipes.json already exists; skipping");
            return;
        }

        try {
            GTReflectionDump.DumpRoot root = new GTReflectionDump.DumpRoot();
            root.generatedAt = new Date().toString();
            root.minecraft = "1.7.10";
            root.mod = MODID;
            root.recipeMaps = GTReflectionDump.dumpAllRecipeMaps();

            File tmp = new File(outFile.getAbsolutePath() + ".tmp");
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

            System.out.println("[" + MODID + "] wrote " + outFile.getAbsolutePath());

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
