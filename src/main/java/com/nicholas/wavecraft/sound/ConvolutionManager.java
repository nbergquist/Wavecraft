package com.nicholas.wavecraft.sound;

import com.mojang.blaze3d.audio.OggAudioStream;
import com.mojang.blaze3d.audio.SoundBuffer;
import com.nicholas.wavecraft.debug.SoundDebugger;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.lwjgl.openal.AL10;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;


public class ConvolutionManager {


    private static long lastCaptureTick = 0;
    private static final long TIMEOUT_TICKS = 1; // 20 ticks por segundo

    private static final Map<String, IRBuilder> impulseResponses = new HashMap<>();

    private static final Map<ResourceLocation, short[]> pcmCache = new HashMap<>(); // Caché para los datos PCM de los sonidos

    // Este record sirve para empaquetar el resultado del cálculo en el hilo secundario.
    private record ConvolvedAudio(short[] pcm, int sampleRate, boolean isRightEar) {}


    private static String generateKey(RayImpulseCapture cap) {
        // p.ej. "minecraft:ambient.cave_R" o "_L"
        return cap.soundId().toString() + (cap.isRightEar() ? "_R" : "_L");
    }

    public static class IRBuilder {
        private final ResourceLocation srcId;
        private final boolean rightEar;
        private final long seed;
        private final FloatArrayList taps = new FloatArrayList();
        private final int sampleRate = 44100; // mismo que los OGG vanilla

        private final int maxIRLengthSamples = (int)(2.0 * sampleRate); // Límite de 2 segundos

        public IRBuilder(ResourceLocation srcId, boolean rightEar, long seed) {
            this.srcId = srcId;
            this.rightEar = rightEar;
            this.seed = seed;
        }

        public long getSeed() {
            return seed;
        }

        public void add(RayImpulseCapture c) {
            int index = Math.round(c.timeSeconds() * sampleRate);

            // Si el eco llega más tarde del límite, lo ignoramos por completo.
            // Esto evita que el array crezca demasiado y cause el error de memoria.
            if (index >= maxIRLengthSamples) {
                return;
            }

            ensureSize(index);
            float current = taps.getFloat(index);
            //float added = c.weight() * c.totalAttenuation();
            float added = c.weight() * c.totalAttenuation() * 50.0f;
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

    /**
     * Carga el sonido original, lo convoluciona con la IR generada y reproduce el resultado final.
     * Esta versión corregida integra toda la lógica que faltaba.
     * Inicia el proceso de convolución de forma ASÍNCRONA para no bloquear el juego.
     */
    private static void processAndPlayResponses() {
        // Hacemos una copia del mapa para los hilos de fondo...
        Map<String, IRBuilder> responsesToProcess = new HashMap<>(impulseResponses);
        // ...y limpiamos el original inmediatamente para que pueda recibir nuevos sonidos.
        impulseResponses.clear();

        for (IRBuilder irBuilder : responsesToProcess.values()) {

            // CompletableFuture.supplyAsync() ejecuta el código en un hilo de fondo.
            CompletableFuture.supplyAsync(() -> {

                // --- ESTO SE EJECUTA EN SEGUNDO PLANO ---

                // 1. Cargar el sonido original (I/O)
                short[] originalPcm = loadSoundPCM(irBuilder.getSourceId(), irBuilder.getSeed());
                if (originalPcm == null) return null; // Si falla, devolvemos null

                // 2. Hornear la respuesta al impulso
                short[] impulseResponsePcm = irBuilder.bakePCM();

                System.out.println("Wavecraft Debug: Sonido Original Longitud = " + originalPcm.length);
                System.out.println("Wavecraft Debug: Respuesta al Impulso Longitud = " + impulseResponsePcm.length);


                // 3. Convolucionar (Cálculo pesado)
                short[] convolvedPcm = convolve(originalPcm, impulseResponsePcm);
                if (convolvedPcm == null) return null;

                // 4. Aplicar el Pitch
                float pitch = getOriginalPitch(irBuilder.getSourceId());
                int pitchedSampleRate = Math.max(4000, Math.min(96000,
                        (int)(irBuilder.sampleRate * pitch)));

                // 5. Empaquetar el resultado para devolverlo al hilo principal
                return new ConvolvedAudio(convolvedPcm, pitchedSampleRate, irBuilder.isRightEar());

            }).thenAcceptAsync(result -> {

                // --- ESTO SE EJECUTA DE VUELTA EN EL HILO PRINCIPAL DE MINECRAFT ---

                if (result != null) {
                    // 6. Reproducir el audio ya procesado. Esta parte es rápida.
                    playRawPCM(result.pcm(), result.sampleRate(), result.isRightEar());
                    System.out.println("[DEBUG AUDIO] Reproduciendo audio convolucionado...");

                }

            }, Minecraft.getInstance()); // El segundo argumento asegura que se ejecute en el hilo de MC
        }
    }
    /** Se llama tan pronto generas cada captura */
    /*public static void schedule(RayImpulseCapture cap, long currentTick) {
        // Convertimos segundos a ticks de MC (20 ticks = 1 s)
        long delayTicks = Math.round(cap.timeSeconds() * 20);
        long play! = currentTick + delayTicks;  // SoundDebugger.currentTick ℜcite�turn0file4�
        scheduled.add(new ScheduledImpulse(cap, playTick));
    }*/

    /** Llamar desde el ciclo de tick para disparar sonidos */
    public static void tick(long currentTick) {
        // La única responsabilidad del tick ahora es comprobar el timeout
        // para procesar la Respuesta al Impulso (IR) de la convolución.
        if (lastCaptureTick != 0 && currentTick - lastCaptureTick >= TIMEOUT_TICKS) {
            processAndPlayResponses();
            lastCaptureTick = 0; // Reiniciar el contador
        }
    }

    public static void addCapture(RayImpulseCapture capture, long currentTick) {
        lastCaptureTick = currentTick;
        String key = generateKey(capture);
        impulseResponses.computeIfAbsent(key, k -> new IRBuilder(
                capture.soundId(),
                capture.isRightEar(),
                capture.sourcePos().hashCode()
        )).add(capture);
    }

    public static short[] processImpulsesFor(ResourceLocation soundId) {
        String keyLeft = soundId.toString() + "_L";
        String keyRight = soundId.toString() + "_R";

        IRBuilder irLeft = impulseResponses.remove(keyLeft);
        IRBuilder irRight = impulseResponses.remove(keyRight);

        if (irLeft == null && irRight == null) {
            System.err.println("[ConvolutionManager] ❌ No hay IRs para " + soundId);
            return new short[0];
        }

        // Usamos solo el oído izquierdo por ahora (puedes implementar binaural luego)
        IRBuilder ir = (irLeft != null) ? irLeft : irRight;

        short[] rawShorts = loadSoundPCM(ir.getSourceId(), ir.getSeed());
        if (rawShorts == null) {
            System.err.println("[ConvolutionManager] ❌ No se pudo cargar el audio original para " + soundId);
            return new short[0];
        }

        short[] impulse = ir.bakePCM();  // esto convierte la IR a señal PCM
        return convolve(rawShorts, impulse);  // ← finalmente hacemos la convolución
    }

    private static void processSingleResponse(IRBuilder irBuilder) {
        CompletableFuture.supplyAsync(() -> {
            short[] originalPcm = loadSoundPCM(irBuilder.getSourceId(), irBuilder.getSeed());
            if (originalPcm == null) return null;
            short[] impulseResponsePcm = irBuilder.bakePCM();
            short[] convolvedPcm = convolve(originalPcm, impulseResponsePcm); // Usamos la nueva versión
            if (convolvedPcm == null) return null;
            float pitch = getOriginalPitch(irBuilder.getSourceId());
            int pitchedSampleRate = Math.max(4000, Math.min(96000,
                    (int)(irBuilder.sampleRate * pitch)));
            return new ConvolvedAudio(convolvedPcm, pitchedSampleRate, irBuilder.isRightEar());
        }).thenAcceptAsync(result -> {
            if (result != null) playRawPCM(result.pcm, result.sampleRate, result.isRightEar);
        }, Minecraft.getInstance());
    }

    /**
     * Carga y decodifica un archivo de sonido .ogg, resolviendo primero el
     * ID del evento de sonido a la ruta de archivo real.
     * @return El buffer de audio como un array de shorts, o null si hay un error.
     */
    private static short[] loadSoundPCM(ResourceLocation soundEventId, long seed) {
        // Comprobar si el sonido ya está en la caché
        if (pcmCache.containsKey(soundEventId)) {
            return pcmCache.get(soundEventId);
        }

        SoundManager soundManager = Minecraft.getInstance().getSoundManager();

        // 1. Resolver el ID del evento de sonido para obtener los posibles sonidos.
        WeighedSoundEvents weighedSoundEvents = soundManager.getSoundEvent(soundEventId);
        if (weighedSoundEvents == null) {
            System.err.println("Wavecraft: No se encontró el evento de sonido: " + soundEventId);
            return null;
        }

        // 2. Obtener un sonido específico de la lista (por simplicidad, tomamos el primero).
        // Minecraft normalmente elige uno al azar.
        final RandomSource random = RandomSource.create(seed);
        Sound sound = weighedSoundEvents.getSound(random);
        if (sound == null) {
            System.err.println("Wavecraft: El evento de sonido " + soundEventId + " no tiene sonidos asociados.");
            return null;
        }

        // 3. ¡OBTENER LA RUTA CORRECTA! Este es el ResourceLocation del archivo .ogg
        ResourceLocation soundFilePath = sound.getLocation();

        // 4. Construir la ruta completa al archivo .ogg usando la ruta correcta.
        ResourceLocation fullPath = soundFilePath.withPath(path -> "sounds/" + path + ".ogg");

        try {
            // 5. Obtener el gestor de recursos y el recurso como un stream de datos.
            ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
            Resource resource = resourceManager.getResource(fullPath).orElse(null);
            if (resource == null) {
                System.err.println("Wavecraft: Recurso no encontrado en la ruta de archivo resuelta: " + fullPath);
                return null;
            }

            // 6. Usar el decodificador OGG de Minecraft para leer el stream.
            try (InputStream inputStream = resource.open();
                 OggAudioStream oggStream = new OggAudioStream(inputStream)) {

                ByteBuffer byteBuffer = oggStream.readAll();
                ShortBuffer shortBuffer = byteBuffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                short[] pcm = new short[shortBuffer.remaining()];
                shortBuffer.get(pcm);

                pcmCache.put(soundEventId, pcm);

                return pcm;
            }

        } catch (IOException e) {
            System.err.println("Wavecraft: Error de I/O al cargar el sonido: " + fullPath);
            e.printStackTrace();
            return null;
        }
    }

    /*private static short[] convolve(short[] originalSound, short[] impulseResponse) {
        if (originalSound == null || originalSound.length == 0 || impulseResponse == null || impulseResponse.length == 0) return null;

        // --- INICIO DE LA LÓGICA OVERLAP-ADD ---

        final int BLOCK_SIZE = 8192; // Tamaño de los bloques en los que dividimos la IR. Potencia de 2 es eficiente.
        final int M = originalSound.length;
        final int N = BLOCK_SIZE;

        // 1. Calcular el tamaño de FFT para cada bloque.
        int fftSize = 1;
        while (fftSize < M + N - 1) {
            fftSize <<= 1;
        }

        System.out.println("[FFT-OA DEBUG] Convolución por bloques. Tamaño de FFT por bloque: " + fftSize);

        // 2. Pre-calcular la FFT del sonido original (se reutilizará para cada bloque)
        FastFourierTransformer transformer = new FastFourierTransformer(DftNormalization.STANDARD);
        Complex[] originalSoundComplex = new Complex[fftSize];
        for (int i = 0; i < M; i++) originalSoundComplex[i] = new Complex(originalSound[i], 0);
        for (int i = M; i < fftSize; i++) originalSoundComplex[i] = Complex.ZERO;
        Complex[] originalSoundFft = transformer.transform(originalSoundComplex, TransformType.FORWARD);

        // 3. Preparar el buffer de salida final
        int finalLength = M + impulseResponse.length - 1;
        double[] output = new double[finalLength];

        // 4. Procesar la IR bloque por bloque
        for (int blockStart = 0; blockStart < impulseResponse.length; blockStart += BLOCK_SIZE) {
            int currentBlockSize = Math.min(BLOCK_SIZE, impulseResponse.length - blockStart);

            // Preparar el bloque actual de la IR para la FFT
            Complex[] irBlockComplex = new Complex[fftSize];
            for (int i = 0; i < currentBlockSize; i++) irBlockComplex[i] = new Complex(impulseResponse[blockStart + i], 0);
            for (int i = currentBlockSize; i < fftSize; i++) irBlockComplex[i] = Complex.ZERO;

            // FFT del bloque de la IR
            Complex[] irBlockFft = transformer.transform(irBlockComplex, TransformType.FORWARD);

            // Multiplicar en frecuencia
            Complex[] resultFft = new Complex[fftSize];
            for (int i = 0; i < fftSize; i++) {
                resultFft[i] = originalSoundFft[i].multiply(irBlockFft[i]);
            }

            // IFFT para obtener el resultado de la convolución del bloque
            Complex[] blockResultComplex = transformer.transform(resultFft, TransformType.INVERSE);

            // Sumar y solapar (Add & Overlap) el resultado en el buffer de salida
            for (int i = 0; i < blockResultComplex.length; i++) {
                int outputIndex = blockStart + i;
                if (outputIndex < finalLength) {
                    // Aplicamos el escalado aquí
                    output[outputIndex] += blockResultComplex[i].getReal() / fftSize;
                }
            }
        }

        // 5. Normalizar el resultado final completo y convertir a short
        double maxVolume = 1e-9;
        for (int i = 0; i < finalLength; i++) {
            if (Math.abs(output[i]) > maxVolume) {
                maxVolume = Math.abs(output[i]);
            }
        }

        short[] finalPcm = new short[finalLength];
        for (int i = 0; i < finalLength; i++) {
            double normalizedSample = output[i] / maxVolume;
            normalizedSample = Math.max(-1.0, Math.min(1.0, normalizedSample));
            finalPcm[i] = (short) (normalizedSample * 32767.0);
        }

        System.out.println("[FFT-OA DEBUG] Convolución finalizada. Pico de volumen: " + maxVolume);
        return finalPcm;
    }*/

    private static short[] convolve(short[] x, short[] h) {

        System.out.println("[DEBUG 1] Inicio convolución. Señal=" +
                (x != null ? x.length : "null") + ", IR=" + (h != null ? h.length : "null"));

        if (x == null || x.length == 0 || h == null || h.length == 0) {
            System.err.println("[ERROR] Señal o IR nulas o vacías.");
            return null;
        }

        final int BLOCK = 8192;
        final int fftSize = Integer.highestOneBit(BLOCK + x.length - 1) << 1;

        System.out.println("[DEBUG 2] fftSize=" + fftSize + " (BLOCK=" + BLOCK + ")");

        FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);

        Complex[] xPad = new Complex[fftSize];
        for (int i = 0; i < x.length; i++) {
            xPad[i] = new Complex(x[i] / 32767.0, 0);
        }
        for (int i = x.length; i < fftSize; i++) {
            xPad[i] = Complex.ZERO;
        }

        System.out.println("[DEBUG 3] FFT de la señal original");
        Complex[] X = fft.transform(xPad, TransformType.FORWARD);

        double[] y = new double[x.length + h.length - 1];
        System.out.println("[DEBUG 4] Búfer de salida preparado. Tamaño: " + y.length);

        for (int start = 0; start < h.length; start += BLOCK) {
            int size = Math.min(BLOCK, h.length - start);
            System.out.println("[DEBUG 5] Bloque IR desde " + start + " tamaño " + size);

            Complex[] hPad = new Complex[fftSize];
            for (int i = 0; i < size; i++) {
                hPad[i] = new Complex(h[start + i] / 32767.0, 0);
            }
            for (int i = size; i < fftSize; i++) {
                hPad[i] = Complex.ZERO;
            }

            Complex[] H = fft.transform(hPad, TransformType.FORWARD);

            for (int i = 0; i < fftSize; i++) {
                H[i] = X[i].multiply(H[i]);
            }

            Complex[] block = fft.transform(H, TransformType.INVERSE);

            for (int i = 0; i < block.length; i++) {
                int outIdx = start + i;
                if (outIdx < y.length) {
                    y[outIdx] += block[i].getReal() / fftSize;
                } else {
                    System.err.println("[WARNING] outIdx fuera de rango: " + outIdx);
                }
            }

            System.out.println("[DEBUG 6] Procesado bloque IR hasta índice " + (start + size - 1));
        }

        System.out.println("[DEBUG 7] Normalización y cuantización");
        double max = 1e-12;
        for (double v : y) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                System.err.println("[ERROR] Valor inválido en salida de convolución: " + v);
            }
            max = Math.max(max, Math.abs(v));
        }

        System.out.println("[DEBUG 8] Máximo absoluto en y = " + max);

        short[] pcm = new short[y.length];
        for (int i = 0; i < y.length; i++) {
            double scaled = y[i] / max;
            scaled = Math.max(-1.0, Math.min(1.0, scaled));
            pcm[i] = (short) Math.round(scaled * 32767);

            // Validación extra
            if (Float.isNaN(pcm[i]) || Float.isInfinite(pcm[i])) {
                System.err.println("[ERROR] Valor inválido en pcm[" + i + "] = " + pcm[i]);
                pcm[i] = 0;
            }
        }

        System.out.println("[DEBUG 9] Convolución finalizada. Longitud PCM: " + pcm.length);
        return pcm;
    }

    /**
     * Normaliza un buffer de audio float para evitar clipping y lo convierte a PCM de 16-bit.
     */
    private static short[] normalizeAndConvertToShort(float[] floatPcm) {
        float maxVal = 0f;
        for (float sample : floatPcm) {
            if (Math.abs(sample) > maxVal) {
                maxVal = Math.abs(sample);
            }
        }

        if (maxVal == 0) maxVal = 1; // Evitar división por cero

        System.out.println("Wavecraft Debug: Pico de volumen máximo antes de normalizar = " + maxVal);


        short[] shortPcm = new short[floatPcm.length];
        for (int i = 0; i < floatPcm.length; i++) {
            float normalizedSample = floatPcm[i] / maxVal;
            shortPcm[i] = (short) (normalizedSample * 32767);
        }
        return shortPcm;
    }

    public static void playRawPCM(short[] pcm, int sampleRate, boolean isRightEar) {
        if (Minecraft.getInstance().player == null || pcm == null || pcm.length == 0) return;

        // --- PREPARACIÓN DEL BUFFER (Sin cambios) ---
        int bufferId = AL10.alGenBuffers();
        checkALError("alGenBuffers");

        ShortBuffer dataBuffer = ByteBuffer.allocateDirect(pcm.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        dataBuffer.put(pcm).flip();
        for (short s : pcm) {
            if (Float.isNaN(s) || Float.isInfinite(s)) {
                System.err.println("Wavecraft: Sample NaN/Inf detectado; abortando playRawPCM()");
                return;
            }
        }
        AL10.alBufferData(bufferId, AL10.AL_FORMAT_MONO16, dataBuffer, sampleRate);
        checkALError("alBufferData");

        // --- PREPARACIÓN DE LA FUENTE (SOURCE) ---
        int sourceId = AL10.alGenSources();
        checkALError("alGenSources");

        AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);

        // --- INICIO DE LA CORRECCIÓN CLAVE ---
        // 1. Hacemos que la fuente de sonido sea RELATIVA al jugador.
        //    Esto significa que su posición no es en el mundo, sino en relación a la "cabeza" del listener.
        AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);

        // 2. Posicionamos el sonido ligeramente a la izquierda o derecha de la cabeza.
        //    x=eje lateral, y=eje vertical, z=eje adelante/atrás.
        float x = isRightEar ? 0.2f : -0.2f; // Un valor pequeño para el paneo.
        AL10.alSource3f(sourceId, AL10.AL_POSITION, x, 0.0f, 0.0f);
        // --- FIN DE LA CORRECCIÓN CLAVE ---

        AL10.alSourcef(sourceId, AL10.AL_GAIN, 1.0f); // Volumen al máximo.
        checkALError("alSource-Setup");

        // --- REPRODUCCIÓN Y LIMPIEZA (Sin cambios) ---
        AL10.alSourcePlay(sourceId);
        checkALError("alSourcePlay");

        new Thread(() -> {
            try {
                // Esperar a que el sonido termine de reproducirse
                while (AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException ignored) {}
            // Liberar recursos
            AL10.alDeleteSources(sourceId);
            AL10.alDeleteBuffers(bufferId);
        }).start();
    }

    public static float getOriginalPitch(ResourceLocation id) {
        return SoundTracker.lastKnownPitches.getOrDefault(id, 1.0f);
    }

    /**
     * Comprueba y muestra en consola cualquier error de OpenAL.
     */
    private static void checkALError(String step) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            System.err.println("Wavecraft: Error de OpenAL en el paso '" + step + "': " + error);
        }
    }
}
