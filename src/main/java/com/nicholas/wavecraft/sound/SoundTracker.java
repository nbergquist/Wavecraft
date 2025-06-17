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
    public static void onPlaySound(PlaySoundEvent event) {
        // 1. Filtros de seguridad
        if (Minecraft.getInstance().level == null || event.getSound() == null || event.getSound() instanceof ReflectedSoundInstance || event.getSound().getSource() == SoundSource.MUSIC) {
            return;
        }

        // 2. Comprobar si el sistema de simulación está activado
        if (SoundDebugger.rayEmissionEnabled) {
            SoundInstance sound = event.getSound();
            ResourceLocation location = sound.getLocation();

            // --- EXTRACCIÓN DE DATOS DEFENSIVA ---
            // Vamos a intentar obtener los datos, pero proporcionaremos valores seguros si algo falla.
            // Esto evitará el crash sin importar el estado en que se encuentre la instancia de sonido.

            Vec3 pos;
            float pitch;
            float volume;

            try {
                pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
            } catch (Exception e) {
                // Si la posición falla, no podemos hacer nada. Lo registramos y salimos.
                System.err.println("[Wavecraft] No se pudo obtener la posición para el sonido: " + location + ". Abortando simulación para este sonido.");
                return; // Dejamos que el sonido original se reproduzca para no perderlo del todo.
            }

            try {
                pitch = sound.getPitch();
            } catch (Exception e) {
                System.err.println("[Wavecraft] No se pudo obtener el pitch para el sonido: " + location + ". Usando 1.0 por defecto.");
                pitch = 1.0f;
            }

            try {
                // Este es el bloque que captura el error del crash report.
                volume = sound.getVolume();
            } catch (Exception e) {
                System.err.println("[Wavecraft] CRÍTICO: Fallo al obtener el volumen para el sonido: " + location + ". Esta era la causa del crash. Usando 1.0 por defecto.");
                volume = 1.0f;
            }
            // --- FIN DE LA EXTRACCIÓN DEFENSIVA ---

            // El resto del flujo es el que ya habíamos validado
            lastKnownPitches.put(location, pitch);
            SoundDebugger.queuedSounds.add(new SoundDebugger.QueuedSound(location, pos, volume));
            System.out.println("[SoundTracker] ✅ Encolado para reemplazar: " + location);

            // Cancelamos el sonido original
            event.setSound(null);
        }
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

