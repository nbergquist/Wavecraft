package com.nicholas.wavecraft.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.ModLoadingContext;

@Mod.EventBusSubscriber
public class WavecraftConfig {

    public static final ForgeConfigSpec COMMON_CONFIG;
    public static final ForgeConfigSpec.BooleanValue DEBUG_SHADER_OUTPUT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        DEBUG_SHADER_OUTPUT = builder
                .comment("Muestra la salida del shader de rayos por consola")
                .define("debugShaderOutput", false);

        COMMON_CONFIG = builder.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, WavecraftConfig.COMMON_CONFIG);
    }
}
