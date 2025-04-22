package com.nicholas.wavecraft.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.nicholas.wavecraft.debug.SoundDebugger;

public class WavecraftCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("wc")
                        .then(Commands.literal("RenderSPL")
                                .executes(ctx -> {
                                    SoundDebugger.renderSPL = !SoundDebugger.renderSPL;
                                    ctx.getSource().sendSuccess(() ->
                                            SoundDebugger.renderSPL ?
                                                    net.minecraft.network.chat.Component.literal("RenderSPL ON") :
                                                    net.minecraft.network.chat.Component.literal("RenderSPL OFF"), true);
                                    return 1;
                                })
                        )
        );
    }
}
