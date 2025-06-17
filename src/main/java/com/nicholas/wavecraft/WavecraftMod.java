package com.nicholas.wavecraft;

import com.nicholas.wavecraft.commands.WavecraftCommand;
import com.nicholas.wavecraft.config.WavecraftConfig;
import com.nicholas.wavecraft.debug.CacheEventHandler;
import com.nicholas.wavecraft.debug.SoundDebugger;
import com.nicholas.wavecraft.sound.RayShaderHandler;
import com.nicholas.wavecraft.sound.SoundModificationListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// La anotación @Mod le dice a Forge que esta clase es la principal de un mod.
@Mod(WavecraftMod.MODID)
public class WavecraftMod {
    public static final String MODID = "wavecraft";
    private static final Logger LOGGER = LogManager.getLogger();

    public WavecraftMod() {
        // --- REGISTRO DE EVENTOS ---
        // Obtenemos los dos buses de eventos.
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        IEventBus forgeEventBus = MinecraftForge.EVENT_BUS;

        // --- Eventos del Ciclo de Vida del Mod (se registran en el MOD bus) ---
        modEventBus.addListener(this::onClientSetup);
        //modEventBus.addListener(this::onRegisterReloadListeners);

        // --- Eventos del Juego (se registran en el FORGE bus) ---
        // Registramos instancias de nuestras clases que contienen lógica de eventos.
        forgeEventBus.register(new CacheEventHandler());
        forgeEventBus.register(new SoundDebugger());

        // El registro de comandos también es un evento del juego.
        forgeEventBus.addListener(this::onRegisterCommands);

        WavecraftConfig.register();

        LOGGER.info("Wavecraft Mod: Buses de eventos configurados correctamente.");
    }

    /**
     * Este método se ejecuta en el bus del MOD, solo durante la carga del cliente.
     * Es el lugar seguro para inicializar cosas que dependen de OpenGL.
     */
    private void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            LOGGER.info("Encolando inicialización de RayShaderHandler en el hilo de renderizado...");
            RayShaderHandler.init();
        });
    }

    /**
     * Este método se ejecuta en el bus de FORGE cuando el servidor (integrado o dedicado) está listo para registrar comandos.
     */
    private void onRegisterCommands(final RegisterCommandsEvent event) {
        WavecraftCommand.register(event.getDispatcher());
        LOGGER.info("Comandos de Wavecraft registrados.");
    }

    /*public void onRegisterReloadListeners(final RegisterClientReloadListenersEvent event) {
        System.out.println("[Wavecraft] ==> PASO 1: Registrando el SoundModificationListener (MÉTODO EXPLÍCITO).");
        event.registerReloadListener(new SoundModificationListener());
    }*/
}