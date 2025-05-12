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
                                            AcousticRayManager.setSoundSpeed(value);
                                            ctx.getSource().sendSuccess(() ->
                                                    Component.literal("Velocidad del sonido ajustada a " + value + " m/s"), true);
                                            return 1;
                                        }))

                        )
                        .then(Commands.literal("setNumRays")
                                .then(Commands.argument("value", IntegerArgumentType.integer(1, 10000))
                                        .executes(ctx -> {
                                            int value = IntegerArgumentType.getInteger(ctx, "value");
                                            AcousticRayManager.setNumRays(value);
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


        );
    }
}
