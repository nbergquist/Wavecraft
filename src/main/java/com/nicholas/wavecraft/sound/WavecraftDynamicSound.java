package com.nicholas.wavecraft.sound;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public class WavecraftDynamicSound extends AbstractSoundInstance {

    private final ByteBuffer byteBuffer;

    public WavecraftDynamicSound(ResourceLocation id, short[] samples, float volume) {
        super(
                id,
                SoundSource.MASTER,
                SoundInstance.createUnseededRandom()
        );

        this.volume = volume;
        this.byteBuffer = ByteBuffer.allocateDirect(samples.length * 2).order(ByteOrder.nativeOrder());
        ShortBuffer shortBuffer = this.byteBuffer.asShortBuffer();
        shortBuffer.put(samples);
        shortBuffer.flip();  // << IMPORTANTE
        this.byteBuffer.position(0);  // << SUPER IMPORTANTE

    }


    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }

    public int getSampleRate() {
        return 44100; // o el que uses al convolucionar
    }

    public void play() {
        if (byteBuffer == null) {
            System.err.println("[WavecraftDynamicSound] ❌ Buffer vacío.");
            return;
        }

        int bufferId = AL10.alGenBuffers();
        AL10.alBufferData(bufferId, AL10.AL_FORMAT_MONO16, byteBuffer, getSampleRate());

        int sourceId = AL10.alGenSources();
        AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);
        AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);  // volumen del sonido
        AL10.alSourcePlay(sourceId);

        System.out.println("[WavecraftDynamicSound] ▶️ Sonido reproducido con OpenAL");
        System.out.println("[DEBUG] byteBuffer capacity = " + byteBuffer.capacity());
        System.out.println("[DEBUG] byteBuffer remaining = " + byteBuffer.remaining());
        System.out.println("[DEBUG] byteBuffer position = " + byteBuffer.position());
        System.out.println("[DEBUG] SampleRate = " + getSampleRate());
    }
}
