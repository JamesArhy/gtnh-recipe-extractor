package com.jamie.gtnh.recipedumper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jamie.gtnh.recipedumper.gt.GTReflectionDump;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
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
        File configDir = e.getModConfigurationDirectory();
        File outDir = new File(configDir, MODID);
        if (!outDir.exists()) outDir.mkdirs();
        outFile = new File(outDir, "recipes.json");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onServerStarted(FMLServerStartedEvent e) {
        try {
            if (outFile.exists() && outFile.length() > 0) {
                log("recipes.json already exists; skipping: " + outFile.getAbsolutePath());
                return;
            }

            GTReflectionDump.DumpRoot root = new GTReflectionDump.DumpRoot();
            root.generatedAt = new Date().toString();
            root.minecraft = "1.7.10";
            root.mod = MODID + " 0.1.0";

            log("Dumping GregTech RecipeMaps via reflection...");
            root.recipeMaps = GTReflectionDump.dumpAllRecipeMaps();

            writeAtomically(outFile, GSON.toJson(root));
            log("Wrote: " + outFile.getAbsolutePath());

            // Make the CI run exit once we’re done
            e.getServer().initiateShutdown();

        } catch (Throwable t) {
            t.printStackTrace();
            log("ERROR: " + t.getMessage());
        }
    }

    private void writeAtomically(File file, String content) throws Exception {
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (FileWriter fw = new FileWriter(tmp)) {
            fw.write(content);
        }
        if (file.exists()) file.delete();
        Files.move(tmp.toPath(), file.toPath());
    }

    private void log(String msg) {
        System.out.println("[" + MODID + "] " + msg);
    }
}
