package com.nicholas.wavecraft.sound;

import com.nicholas.wavecraft.debug.SoundDebugger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.sound.PlaySoundSourceEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;



import java.util.*;

//import static com.nicholas.wavecraft.debug.SoundDebugger.currentTick;

@Mod.EventBusSubscriber(modid = "wavecraft", value = Dist.CLIENT)
public class SoundTracker {

    public static class ActiveSound {
        public final Vec3 position;
        public final float volume;
        public final long timestamp;



        public ActiveSound(Vec3 pos, float vol) {
            this.position = pos;
            this.volume = vol;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final List<ActiveSound> activeSounds = new ArrayList<>();
    public static final Map<ResourceLocation, Float> lastKnownPitches = new HashMap<>();
    private static final long MAX_LIFETIME_MS = 2000; // mantener sonidos 2s

    /*@SubscribeEvent
    public static void onSoundSourcePlayed(PlaySoundSourceEvent event) {
        if (Minecraft.getInstance().level == null) {
            System.out.println("[DEBUG] Ignorando sonido (no hay mundo cargado)");
            return;
        }

        SoundInstance sound = event.getSound();
        if (sound == null || sound instanceof ReflectedSoundInstance) {
            return;
        }

        Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        float vol = sound.getVolume();

        if (SoundDebugger.rayEmissionEnabled) {
            if (sound.getLocation().getPath().startsWith("reflected_")) return;  // Ignorar sonidos de la convolución

            float pitch = sound.getPitch();
            SoundTracker.lastKnownPitches.put(sound.getLocation(), pitch);

            long worldTime = Minecraft.getInstance().level.getGameTime();
            AcousticRayManager.getInstance().emitRays(pos, sound.getLocation(), worldTime);


            System.out.println("[SoundTracker] ✅ Capturado: " +
                    sound.getLocation() +
                    " en " + pos +
                    " | volumen=" + vol);
        }

    }*/

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        // 1. Filtros de seguridad: ignorar si no hay mundo, el sonido es nulo, es uno de nuestros propios ecos, o es música.
        if (Minecraft.getInstance().level == null || event.getSound() == null || event.getSound() instanceof ReflectedSoundInstance || event.getSound().getSource() == SoundSource.MUSIC) {
            return;
        }

        SoundInstance eventSound = event.getSound();

        // 2. Filtro para sonidos no posicionales: ignorar sonidos de la UI y otros sonidos relativos.
        // Dejamos que Minecraft los reproduzca normalmente.
        if (eventSound.isRelative()) {
            return;
        }

        // 3. Comprobar si nuestro sistema de simulación está activado.
        if (SoundDebugger.rayEmissionEnabled) {
            ResourceLocation location = eventSound.getLocation();
            Vec3 pos = new Vec3(eventSound.getX(), eventSound.getY(), eventSound.getZ());

            // --- INICIO DE LA LÓGICA DE EXTRACCIÓN SEGURA DE PROPIEDADES ---
            // Se va directamente al SoundManager para obtener los valores base del sonido,
            // evitando el objeto incompleto del evento.
            SoundManager soundManager = Minecraft.getInstance().getSoundManager();
            WeighedSoundEvents weighedSoundEvents = soundManager.getSoundEvent(location);

            float volume = 1.0f;
            float pitch = 1.0f;

            if (weighedSoundEvents != null) {
                // Obtenemos un sonido de muestra para leer sus propiedades por defecto de sounds.json
                Sound defaultSound = weighedSoundEvents.getSound(RandomSource.create());
                if (defaultSound != null) {
                    // Estos son los valores base del archivo de sonido
                    volume = defaultSound.getVolume().sample(RandomSource.create());
                    pitch = defaultSound.getPitch().sample(RandomSource.create());
                } else {
                    System.err.println("[Wavecraft] ADVERTENCIA: El evento de sonido " + location + " no tiene sonidos asociados.");
                }
            } else {
                System.err.println("[Wavecraft] ADVERTENCIA: No se encontraron propiedades base para " + location + " en SoundManager. Usando valores por defecto.");
            }
            // --- FIN DE LA LÓGICA DE EXTRACCIÓN SEGURA ---

            // Guardamos el pitch y encolamos el sonido para el procesamiento de rayos
            lastKnownPitches.put(location, pitch);
            SoundDebugger.queuedSounds.add(new SoundDebugger.QueuedSound(location, pos, volume));

            System.out.println("[SoundTracker] ✅ Capturado: " +
                    location +
                    " en " + pos +
                    " | volumen=" + volume);

            // Cancelamos el sonido original para reemplazarlo por nuestra simulación
            event.setSound(null);
        }
    }

    @SubscribeEvent
    public static void onRegisterReloadListener(RegisterClientReloadListenersEvent event) {
        // Registramos una instancia de nuestra nueva clase.
        System.out.println("[Wavecraft] ==> PASO 1: Registrando el SoundModificationListener.");

        event.registerReloadListener(new SoundModificationListener());
    }


    /*@SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        // 1. Filtros de seguridad (sin cambios)
        if (Minecraft.getInstance().level == null || event.getSound() == null || event.getSound() instanceof ReflectedSoundInstance || event.getSound().getSource() == SoundSource.MUSIC) {
            return;
        }

        // 2. Comprobar si el sistema está activo
        if (SoundDebugger.rayEmissionEnabled) {
            SoundInstance sound = event.getSound();
            Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());

            // 3. Guardar el pitch (sin cambios)
            try {
                lastKnownPitches.put(sound.getLocation(), sound.getPitch());
            } catch (NullPointerException e) {
                lastKnownPitches.put(sound.getLocation(), 1.0f);
            }

            // 4. ¡ACCIÓN CLAVE! Simplemente emitir los rayos.
            // El AcousticRayManager se encargará del resto en sus ticks.
            AcousticRayManager.getInstance().emitRays(pos, sound.getLocation(), Minecraft.getInstance().level.getGameTime());

            System.out.println("[SoundTracker] ✅ Capturado y REEMPLAZANDO: " + sound.getLocation());

            // 5. Cancelar el sonido original (sin cambios)
            event.setSound(null);
        }
    }*/

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END && Minecraft.getInstance().level != null) {
            // El tick del ray manager sigue siendo necesario para actualizar las animaciones y expirar los rayos
            AcousticRayManager.getInstance().tick(Minecraft.getInstance().level, Minecraft.getInstance().level.getGameTime());
        }
    }
}

