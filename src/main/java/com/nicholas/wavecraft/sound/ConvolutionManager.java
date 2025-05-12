package com.nicholas.wavecraft.sound;

import com.mojang.blaze3d.audio.SoundBuffer;
import com.nicholas.wavecraft.debug.SoundDebugger;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.phys.Vec3;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.lwjgl.openal.AL10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;


public class ConvolutionManager {

    private static final List<ScheduledImpulse> scheduled = new ArrayList<>();

    private static long lastCaptureTick = 0;
    private static final long TIMEOUT_TICKS = 40; // 2 segundos a 20 ticks por segundo

    private static final Map<String, IRBuilder> impulseResponses = new HashMap<>();

    private static String generateKey(RayImpulseCapture cap) {
        // p.ej. "minecraft:ambient.cave_R" o "_L"
        return cap.soundId().toString() + (cap.isRightEar() ? "_R" : "_L");
    }

    private static class ScheduledImpulse {
        RayImpulseCapture cap;
        long playTick;
        ScheduledImpulse(RayImpulseCapture cap, long playTick) {
            this.cap = cap;
            this.playTick = playTick;
        }
    }

    public static class IRBuilder {
        private final ResourceLocation srcId;
        private final boolean rightEar;
        private final FloatArrayList taps = new FloatArrayList();
        private final int sampleRate = 44100; // mismo que los OGG vanilla

        public IRBuilder(ResourceLocation srcId, boolean rightEar) {
            this.srcId = srcId;
            this.rightEar = rightEar;
        }

        public void add(RayImpulseCapture c) {
            int index = Math.round(c.timeSeconds() * sampleRate);
            ensureSize(index);
            float current = taps.getFloat(index);
            float added = c.weight() * c.totalAttenuation();
            taps.set(index, current + added);
        }

        public short[] bakePCM() {
            short[] pcm = new short[taps.size()];
            for (int i = 0; i < pcm.length; i++) {
                float clamped = clamp(taps.getFloat(i), -1f, 1f);
                pcm[i] = (short) Math.round(clamped * 32767);
            }
            return pcm;
        }

        public ResourceLocation getSourceId() {
            return srcId;
        }

        public boolean isRightEar() {
            return rightEar;
        }

        private void ensureSize(int index) {
            while (taps.size() <= index) taps.add(0f);
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /** Crea la respuesta al impulso y la dispara via play(...). */
    private static void processAndPlayResponses() {
        for (IRBuilder ir : impulseResponses.values()) {
            short[] pcm = ir.bakePCM();

            // Crear y reproducir un sonido convolucionado:
            // (Asumiendo que tienes una clase tipo ConvolvedSoundPlayer)
            playRawPCM(
                    pcm,
                    ir.sampleRate,
                    ir.isRightEar()
            );
        }

        impulseResponses.clear();
    }

    /** Se llama tan pronto generas cada captura */
    /*public static void schedule(RayImpulseCapture cap, long currentTick) {
        // Convertimos segundos a ticks de MC (20 ticks = 1 s)
        long delayTicks = Math.round(cap.timeSeconds() * 20);
        long playTick = currentTick + delayTicks;  // SoundDebugger.currentTick ℜcite�turn0file4�
        scheduled.add(new ScheduledImpulse(cap, playTick));
    }*/

    /** Llamar desde el ciclo de tick para disparar sonidos */
    public static void tick(long currentTick) {
        // 1) Dispara los ScheduledImpulse inmediatos
        Iterator<ScheduledImpulse> it = scheduled.iterator();
        while (it.hasNext()) {
            ScheduledImpulse si = it.next();
            if (si.playTick <= currentTick) {
                play(si.cap);
                it.remove();
            }
        }

        // 2) Timeout de IR: 2 segundos sin nuevas capturas → procesa IR
        if (lastCaptureTick != 0 && currentTick - lastCaptureTick >= TIMEOUT_TICKS) {
            processAndPlayResponses();
            impulseResponses.clear();
            lastCaptureTick = 0;
        }
    }

    public static void addCapture(RayImpulseCapture capture, long currentTick) {
        lastCaptureTick = currentTick;
        // Lógica para almacenar la captura en tu mapa de IRs
        String key = generateKey(capture);
        impulseResponses
                .computeIfAbsent(key, k -> new IRBuilder(capture.soundId(), capture.isRightEar()))
                .add(capture);
    }

    /** Reproduce el sonido con la atenuación y paneo calculados */
    private static void play(RayImpulseCapture cap) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        Vec3 headPos = player.getEyePosition();
        Vec3 rightVec = player.getLookAngle().cross(new Vec3(0, 1, 0)).normalize();
        Vec3 emitPos = headPos.add(rightVec.scale(cap.isRightEar() ? 0.1 : -0.1));

        // 1. Atenuación por distancia: inversa cuadrada (puedes usar solo inversa si prefieres)
        double d = cap.distanceFromSource();
        float divergence = (float)(1.0 / Math.max(d * d, 1.0));  // evitar división por cero

        // 2. Absorción acumulada: usa cap.totalAttenuation() si en el futuro lo aplicas
        float absorption = cap.totalAttenuation();  // actualmente es 1.0 por defecto

        // 3. Peso angular binaural ya aplicado en cap.weight()
        float volume = cap.weight() * divergence * absorption;
        float pitch = ConvolutionManager.getOriginalPitch(cap.soundId());


        // Usa directamente el ResourceLocation si cap.soundId() ya lo devuelve así
        ResourceLocation soundLocation = cap.soundId();
        SoundEvent soundEvent = BuiltInRegistries.SOUND_EVENT.get(soundLocation);

        if (soundEvent == null) {
            System.out.println("SoundEvent no encontrado para: " + soundLocation);
            return;
        }

        ReflectedSoundInstance inst = new ReflectedSoundInstance(
                soundEvent.getLocation(),
                SoundSource.AMBIENT,
                volume,
                pitch,                   // Pitch normal
                Minecraft.getInstance().level.random,
                false,                  // No repetir
                0,                      // Delay de repetición
                SoundInstance.Attenuation.LINEAR,
                (float) emitPos.x,
                (float) emitPos.y,
                (float) emitPos.z,
                false                   // ¿Es relativo al jugador? False para 3D
        );

        Minecraft.getInstance().getSoundManager().play(inst);
    }

    public static void playRawPCM(short[] pcm, int sampleRate, boolean rightEar) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        // 1. Obtener posición de la cabeza y aplicar pequeño offset lateral (binaural)
        Vec3 headPos = player.getEyePosition();
        Vec3 rightVec = player.getLookAngle().cross(new Vec3(0, 1, 0)).normalize();
        Vec3 emitPos = headPos.add(rightVec.scale(rightEar ? 0.1 : -0.1));

        // 2. Crear un buffer de audio
        int bufferId = AL10.alGenBuffers();
        ShortBuffer dataBuffer = ByteBuffer
                .allocateDirect(pcm.length * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer();
        dataBuffer.put(pcm).flip();

        AL10.alBufferData(bufferId, AL10.AL_FORMAT_MONO16, dataBuffer, sampleRate);

        // 3. Crear un source para reproducirlo en 3D
        int sourceId = AL10.alGenSources();
        AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);
        AL10.alSource3f(sourceId, AL10.AL_POSITION,
                (float) emitPos.x,
                (float) emitPos.y,
                (float) emitPos.z);

        // 4. Reproducir
        AL10.alSourcePlay(sourceId);

        // 5. Programar liberación del recurso cuando termine de sonar
        new Thread(() -> {
            try {
                while (AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException ignored) {}
            AL10.alDeleteSources(sourceId);
            AL10.alDeleteBuffers(bufferId);
        }).start();
    }

    public static float getOriginalPitch(ResourceLocation id) {
        return SoundTracker.lastKnownPitches.getOrDefault(id, 1.0f);
    }

}
