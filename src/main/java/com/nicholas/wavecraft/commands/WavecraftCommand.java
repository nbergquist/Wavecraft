package com.nicholas.wavecraft.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import com.nicholas.wavecraft.debug.SoundDebugger;
import com.nicholas.wavecraft.sound.AcousticRayManager;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.brigadier.arguments.FloatArgumentType;




public class WavecraftCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("wc")
                        .then(Commands.literal("renderSPL")
                                .executes(ctx -> {
                                    SoundDebugger.renderSPL = !SoundDebugger.renderSPL;
                                    ctx.getSource().sendSuccess(() ->
                                            SoundDebugger.renderSPL ?
                                                    Component.literal("RenderSPL ON") :
                                                    Component.literal("RenderSPL OFF"), true);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("renderRays")
                                .executes(ctx -> {
                                    SoundDebugger.renderRays = !SoundDebugger.renderRays;
                                    ctx.getSource().sendSuccess(() ->
                                            SoundDebugger.renderRays ?
                                                    Component.literal("Renderizado de rayos activado") :
                                                    Component.literal("Renderizado de rayos desactivado"), true);
                                    return 1;
                                })
                        )

                        .then(Commands.literal("setSoundSpeed")
                                .then(Commands.argument("value", FloatArgumentType.floatArg(1.0f, 10000.0f))
                                        .executes(ctx -> {
                                            float value = FloatArgumentType.getFloat(ctx, "value");
                                            AcousticRayManager.getInstance().setSoundSpeed(value);
                                            ctx.getSource().sendSuccess(() ->
                                                    Component.literal("Velocidad del sonido ajustada a " + value + " m/s"), true);
                                            return 1;
                                        }))

                        )
                        .then(Commands.literal("setNumRays")
                                .then(Commands.argument("value", IntegerArgumentType.integer(1, 10000))
                                        .executes(ctx -> {
                                            int value = IntegerArgumentType.getInteger(ctx, "value");
                                            AcousticRayManager.getInstance().setNumRays(value);
                                            ctx.getSource().sendSuccess(() ->
                                                    Component.literal("Número de rayos ajustado a " + value), true);
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("toggleRayEmission")
                                .executes(ctx -> {
                                    SoundDebugger.rayEmissionEnabled = !SoundDebugger.rayEmissionEnabled;
                                    ctx.getSource().sendSuccess(() ->
                                            SoundDebugger.rayEmissionEnabled ?
                                                    Component.literal("Emisión de rayos activada") :
                                                    Component.literal("Emisión de rayos desactivada"), true);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("renderCollisionPlanes")
                                .executes(ctx -> {
                                    SoundDebugger.renderCollisionPlanes = !SoundDebugger.renderCollisionPlanes;
                                    ctx.getSource().sendSuccess(() ->
                                            SoundDebugger.renderCollisionPlanes ?
                                                    Component.literal("Renderizado de planos de colisión activado") :
                                                    Component.literal("Renderizado de planos de colisión desactivado"), true);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("setCollisionPlaneSize")
                                // Definimos un argumento llamado "size" que debe ser un número decimal (float)
                                .then(Commands.argument("size", FloatArgumentType.floatArg(0.1f)) // Mínimo de 0.1 para evitar tamaños nulos o negativos
                                        .executes(context -> {
                                            // Obtenemos el valor del argumento que el jugador ha escrito
                                            final float newSize = FloatArgumentType.getFloat(context, "size");

                                            // Actualizamos la variable estática 'dimensions' en SoundDebugger
                                            SoundDebugger.dimensions = newSize;

                                            // Enviamos un mensaje de confirmación al jugador
                                            context.getSource().sendSuccess(() -> Component.literal("Tamaño de los planos de colisión establecido a: " + newSize), true);

                                            // Devolvemos 1 para indicar que el comando se ejecutó correctamente
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("enableRays")
                                .executes(ctx -> {
                                    SoundDebugger.rayEmissionEnabled = !SoundDebugger.rayEmissionEnabled;
                                    ctx.getSource().sendSuccess(() ->
                                            SoundDebugger.rayEmissionEnabled ?
                                                    Component.literal("Cálculo de rayos activado") :
                                                    Component.literal("Cálculo de rayos desactivado"), true);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("setBinauralMix")
                                // El argumento es un factor entre 0.0 y 1.0
                                .then(Commands.argument("factor", FloatArgumentType.floatArg(0.0f, 1.0f))
                                        .executes(context -> {
                                            final float newMix = FloatArgumentType.getFloat(context, "factor");
                                            SoundDebugger.binauralMixFactor = newMix;
                                            context.getSource().sendSuccess(() -> Component.literal("Factor de mezcla binaural establecido a: " + newMix), true);
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("setReflectionsMix")
                                .then(Commands.argument("factor", FloatArgumentType.floatArg(0.0f)) // Mínimo 0.0
                                        .executes(context -> {
                                            final float newMix = FloatArgumentType.getFloat(context, "factor");
                                            SoundDebugger.reflectionsMixFactor = newMix;
                                            context.getSource().sendSuccess(() -> Component.literal("Mezcla de reflexiones establecida a: " + newMix), true);
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("setMasterGain")
                                .then(Commands.argument("gain", FloatArgumentType.floatArg(0.0f)) // Mínimo 0.0
                                        .executes(context -> {
                                            final float newGain = FloatArgumentType.getFloat(context, "gain");
                                            SoundDebugger.masterGain = newGain;
                                            context.getSource().sendSuccess(() -> Component.literal("Ganancia maestra establecida a: " + newGain), true);
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("setEarlyReflectionDampen")
                                .then(Commands.argument("factor", FloatArgumentType.floatArg(0.0f, 1.0f))
                                        .executes(context -> {
                                            final float newFactor = FloatArgumentType.getFloat(context, "factor");
                                            SoundDebugger.earlyReflectionsDamp = newFactor;
                                            context.getSource().sendSuccess(() -> Component.literal("Suavizado de ecos tempranos establecido a: " + newFactor), true);
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("setSoundRange") // <-- Nombre cambiado y más descriptivo
                                .then(Commands.argument("multiplier", FloatArgumentType.floatArg(0.1f))
                                        .executes(context -> {
                                            float newMultiplier = FloatArgumentType.getFloat(context, "multiplier");
                                            SoundDebugger.globalAttenuationMultiplier = newMultiplier;
                                            context.getSource().sendSuccess(() -> Component.literal("Multiplicador de rango de sonido establecido a: " + newMultiplier + "x"), true);
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("toggleStereo")
                                .executes(context -> {
                                    // Invertir el valor del flag
                                    SoundDebugger.binauralModeEnabled = !SoundDebugger.binauralModeEnabled;

                                    // Enviar un mensaje de confirmación al jugador
                                    String status = SoundDebugger.binauralModeEnabled ? "§aBINAURAL (Estéreo)" : "§cMONOAURAL";
                                    context.getSource().sendSuccess(() -> Component.literal("Modo de audio cambiado a: " + status), true);

                                    return 1; // Devuelve 1 para indicar éxito
                                })
                        )
        );
    }
}
