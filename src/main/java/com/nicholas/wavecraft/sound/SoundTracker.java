package com.nicholas.wavecraft.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
    private static final long MAX_LIFETIME_MS = 2000; // mantener sonidos 2s

    @SubscribeEvent
    public static void onSoundPlayed(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null || sound.getSound() == null || sound.getSource() == SoundSource.MASTER) return;

        Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        float vol = sound.getVolume();

        if (vol > 0.01f) {
            activeSounds.add(new ActiveSound(pos, vol));
        }
        System.out.println("[SoundTracker] Captured sound: " + sound.getLocation() + " at " + sound.getX() + ", " + sound.getY() + ", " + sound.getZ() + " | volume=" + vol);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.currentTimeMillis();
        activeSounds.removeIf(s -> now - s.timestamp > MAX_LIFETIME_MS);
    }

    public static List<ActiveSound> getCurrentSounds() {
        return new ArrayList<>(activeSounds);
    }
}
