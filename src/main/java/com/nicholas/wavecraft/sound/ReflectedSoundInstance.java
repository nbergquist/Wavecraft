package com.nicholas.wavecraft.sound;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

public class ReflectedSoundInstance extends SimpleSoundInstance {
    public ReflectedSoundInstance(
            ResourceLocation location,
            SoundSource source,
            float volume,
            float pitch,
            RandomSource random,
            boolean repeat,
            int repeatDelay,
            Attenuation attenuation,
            float x,
            float y,
            float z,
            boolean relative) {
        super(location, source, volume, pitch, random, repeat, repeatDelay, attenuation, x, y, z, relative);
    }
    public boolean isReflected() {
        return true;
    }
}