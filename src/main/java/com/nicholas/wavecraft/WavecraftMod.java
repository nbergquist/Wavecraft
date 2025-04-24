package com.nicholas.wavecraft;
import com.nicholas.wavecraft.debug.SoundDebugger;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.nicholas.wavecraft.commands.WavecraftCommand;

@Mod("wavecraft")
public class WavecraftMod {
    public WavecraftMod() {

        System.out.println("Wavecraft cargado");
        MinecraftForge.EVENT_BUS.register(SoundDebugger.class);
    }

    @Mod.EventBusSubscriber(modid = "wavecraft")
    public static class ModEvents {
        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            WavecraftCommand.register(event.getDispatcher());
        }
    }
}
