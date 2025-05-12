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

    @SubscribeEvent
    public static void onSoundSourcePlayed(PlaySoundSourceEvent event) {
        if (Minecraft.getInstance().level == null) {
            System.out.println("[DEBUG] Ignorando sonido (no hay mundo cargado)");
            return;
        }

        SoundInstance sound = event.getSound();
        if (sound == null) return;

        if (sound instanceof ReflectedSoundInstance) return;

        Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        float vol = sound.getVolume();

        if (vol > 0.01f && SoundDebugger.rayEmissionEnabled) {
            if (sound.getLocation().getPath().startsWith("reflected_")) return;  // Ignorar sonidos de la convolución

            float pitch = sound.getPitch();
            SoundTracker.lastKnownPitches.put(sound.getLocation(), pitch);

            AcousticRayManager.emitRays(pos, sound.getLocation());

            System.out.println("[SoundTracker] ✅ Capturado: " +
                    sound.getLocation() +
                    " en " + pos +
                    " | volumen=" + vol);
        }

    }


    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        long now = System.currentTimeMillis();
        activeSounds.removeIf(s -> now - s.timestamp > MAX_LIFETIME_MS);

        // Procesar los impulsos
        if (Minecraft.getInstance().level != null) {
            long currentTick = Minecraft.getInstance().level.getGameTime();
            ConvolutionManager.tick(currentTick);
        }
    }
}

