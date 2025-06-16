package com.nicholas.wavecraft.sound;

import com.nicholas.wavecraft.debug.SoundDebugger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.sound.PlaySoundSourceEvent;



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
    public static void onPlaySound(PlaySoundEvent event) { // <-- CAMBIO DE NOMBRE Y TIPO DE EVENTO
        // 1. Filtros iniciales de seguridad
        if (Minecraft.getInstance().level == null) {
            return;
        }

        SoundInstance sound = event.getSound();
        if (sound == null || sound instanceof ReflectedSoundInstance || sound.getSource() == SoundSource.MUSIC) {
            return;
        }

        // 2. Comprobamos si nuestro sistema está activado (sin la comprobación de volumen que fallaba)
        if (SoundDebugger.rayEmissionEnabled) {

            Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());

            float pitch;

            try {
                pitch = sound.getPitch();
            } catch (NullPointerException e) {
                pitch = 1.0f;
            }

            // 3. Guardamos el pitch para usarlo después
            lastKnownPitches.put(sound.getLocation(), pitch);

            // 4. Llamamos directamente al AcousticRayManager para generar los rayos
            long worldTime = Minecraft.getInstance().level.getGameTime();
            AcousticRayManager.getInstance().emitRays(pos, sound.getLocation(), worldTime);
            AcousticRayManager.getInstance().tick(Minecraft.getInstance().level, worldTime); // Forzamos un tick para procesar los rayos inmediatamente
            new Thread(() -> {
                short[] convoluted = ConvolutionManager.processImpulsesFor(sound.getLocation());

                if (convoluted == null || convoluted.length == 0) {
                    System.err.println("[ERROR] Audio convolucionado vacío. No se reproduce.");
                    return;
                }

                WavecraftDynamicSound soundToPlay = new WavecraftDynamicSound(sound.getLocation(), convoluted, sound.getVolume());
                soundToPlay.play();
                System.out.println("[DEBUG AUDIO] Reproducción lanzada para: " + sound.getLocation());

            }).start();


            System.out.println("[SoundTracker] ✅ Capturado y REEMPLAZANDO: " + sound.getLocation());
        }

        // 5. El paso CRÍTICO para REEMPLAZAR el sonido original.
        event.setSound(null);
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

