package com.nicholas.wavecraft.debug;

import com.mojang.blaze3d.vertex.*;
import com.nicholas.wavecraft.sound.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.*;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.VertexConsumer; // Importar desde Minecraft
import net.minecraft.sounds.SoundSource;


import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import com.mojang.blaze3d.systems.RenderSystem;


import java.nio.FloatBuffer;
import java.util.*;

import static org.lwjgl.opengl.ARBVertexArrayObject.glBindVertexArray;
import static org.lwjgl.opengl.ARBVertexArrayObject.glGenVertexArrays;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL20.*; // Import estático para usar funciones como glUseProgram directamente
import static org.lwjgl.opengl.GL30.*;

@Mod.EventBusSubscriber(modid = "wavecraft", value = Dist.CLIENT)
public class SoundDebugger {

    public record TrackedSound(ResourceLocation audioFile, Vec3 position, float volume, long tickAdded) {}
    public record QueuedSound(ResourceLocation location, Vec3 position, float volume) {}



    private static final List<AcousticRay.VisualRay> visualRays = new ArrayList<>();

    //public static final List<QueuedSound> queuedSounds = new ArrayList<>();
    //public static final List<TrackedSound> activeSounds = new ArrayList<>();
    private static final List<TrackedSound> activeSounds = Collections.synchronizedList(new ArrayList<>());
    public static final List<QueuedSound> queuedSounds = Collections.synchronizedList(new ArrayList<>());
    private static final List<QueuedSound> soundsForRayEmission = Collections.synchronizedList(new ArrayList<>());
    private static final Map<BlockPos, Double> blockSPLMap = new HashMap<>();

    //public static long currentTick = 0;

    public static boolean renderSPL = false;
    public static boolean renderRays = false;
    public static boolean rayEmissionEnabled = true;
    public static boolean renderRaysDebugMode = false;
    public static boolean renderCollisionPlanes = false;
    public static boolean renderTextureSlice = true;

    public static boolean binauralModeEnabled = true; // Empezamos en estéreo por defecto

    public static float binauralMixFactor = 0.7f;
    public static float reflectionsMixFactor = 1f;
    public static float masterGain = 1;
    //public static float targetReverbLevel = 0.15f;
    public static float earlyReflectionsDamp = 0.6f;

    public static float globalAttenuationMultiplier = 4f;

    public static float dimensions = 50.0f;

    public static int renderProgram;  // Shader para visualizar los rayos

    public List<AcousticRay.VisualRay> getVisualRays() { return visualRays; }
    //public static void addVisualRays(AcousticRay ray) { visualRays.add(ray); }

    public static void addVisualRay(AcousticRay ray, long currentTick) {
        // Extrae el VisualRay actual y clónalo (opcional: copia profunda si lo modificas luego)
        visualRays.add(ray.getVisualRay());
    }

    public static final float maxVisualDistance = 500f;

    //@SubscribeEvent
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;

        long worldTime = mc.level.getGameTime();
        activeSounds.removeIf(s -> worldTime - s.tickAdded() > 40);

        // --- LÓGICA MODIFICADA Y CORREGIDA ---
        // Usamos un bloque sincronizado para iterar de forma segura
        synchronized (queuedSounds) {
            Iterator<QueuedSound> iterator = queuedSounds.iterator();
            while (iterator.hasNext()) {
                QueuedSound queued = iterator.next();

                Vec3 pos = queued.position();
                if (pos.equals(Vec3.ZERO)) {
                    iterator.remove();
                    continue;
                }

                float volume = queued.volume();
                if (volume <= 0) {
                    iterator.remove();
                    continue;
                }

                // --- ¡EL CAMBIO CLAVE ESTÁ AQUÍ! ---
                if (rayEmissionEnabled) {
                    // ANTES: Se llamaba a emitRays, causando un error de hilo.
                    // AHORA: Se añade el sonido a una cola para que el hilo de renderizado lo procese.
                    soundsForRayEmission.add(queued);
                }

                // El resto de la lógica para el mapa de SPL no cambia.
                activeSounds.add(new TrackedSound(queued.location(), pos, volume, worldTime));
                iterator.remove();
            }
        }

        updateSPLMap(mc.level, activeSounds);
        decaySPLValues();

        // Los ticks de los managers siguen aquí para actualizar la lógica que no es de OpenGL
        AcousticRayManager.getInstance().tick(mc.level, worldTime);
        ConvolutionManager.tick(worldTime);
    }

    //@SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        Minecraft mc = Minecraft.getInstance();
        // Si no hay un mundo cargado (estamos en el menú principal),
        // ignoramos TODOS los sonidos y dejamos que se reproduzcan normalmente.
        if (mc.level == null) {
            return;
        }

        SoundInstance sound = event.getSound();
        if (sound == null) return;

        if (sound.getSource() == SoundSource.MUSIC) {
            return;
        }

        // Ignoramos nuestros propios sonidos reflejados para evitar bucles infinitos.
        if (sound instanceof ReflectedSoundInstance) {
            return;
        }

        // --- LÓGICA MODIFICADA ---
        // Extraemos los datos ANTES de cancelar el sonido.
        ResourceLocation location = sound.getLocation();
        Vec3 position = new Vec3(sound.getX(), sound.getY(), sound.getZ());

        float volume;
        try {
            volume = sound.getVolume();
        } catch (NullPointerException e) {
            volume = 1.0f; // Valor de respaldo seguro
            System.out.println("Volume not set");
        }

        // Añadimos el sonido con los datos extraídos a nuestra cola.
        queuedSounds.add(new QueuedSound(location, position, volume));

        // ¡CRÍTICO! Cancelamos el sonido original para que no se reproduzca.
        event.setSound(null);
    }

    public static void updateSPLMap(Level level, List<TrackedSound> activeSounds) {
        if (level == null || activeSounds == null) return;

        for (TrackedSound sound : activeSounds) {
            BlockPos center = BlockPos.containing(sound.position());
            int radius = 8;

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = center.offset(dx, dy, dz);

                        if (!level.getBlockState(pos).isAir()) continue;

                        Vec3 blockCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        double distanceSq = Math.max(1, sound.position().distanceToSqr(blockCenter));

                        double intensity = (sound.volume() * sound.volume()) / distanceSq;
                        double spl = 10 * Math.log10(intensity / 1e-12);

                        if (spl > 0) {
                            blockSPLMap.put(pos.immutable(), spl);
                        }
                    }
                }
            }
        }
    }

    public static void decaySPLValues() {
        blockSPLMap.replaceAll((pos, value) -> Math.max(0, value - 1.0));
    }

    /*public static void addVisualRay(AcousticRay originalRay, long currentTick) {
        AcousticRay visualRay = new AcousticRay(originalRay);
        float totalLength = visualRay.getAcousticDistance();
        visualRay.setExpireTick(currentTick + (long) (totalLength / visualRay.getPropagationSpeed() * 20) + 20);
        visualRays.add(visualRay);

        LOGGER.info("Spawn visual ray: tickLaunched={}, now={}, lifetimeTicks={}, expireTick={}",
                visualRay.getTickLaunched(), currentTick, (long) (totalLength / visualRay.getPropagationSpeed() * 20), visualRay.getExpireTick());
    }*/

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // System.out.println("[ENTRY_DEBUG] SoundDebugger.onRenderLevelStage: Stage=" + event.getStage() + ", renderRays_flag=" + renderRays);

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        // --- NUEVA LÓGICA DE PROCESAMIENTO DE RAYOS ---
        // Se ejecuta en el hilo de renderizado, el lugar correcto para las llamadas a OpenGL.
        if (!soundsForRayEmission.isEmpty()) {
            long worldTime = mc.level.getGameTime();
            // Sincronizamos para evitar problemas si el client tick intentara modificarla al mismo tiempo.
            synchronized (soundsForRayEmission) {
                for (QueuedSound sound : soundsForRayEmission) {
                    // Ahora sí, llamamos a emitRays() de forma segura.
                    AcousticRayManager.getInstance().emitRays(sound.position(), sound.location(), worldTime);
                }
                soundsForRayEmission.clear(); // Limpiamos la cola una vez procesada.
            }
        }

        if (!renderRays && !renderCollisionPlanes) { // Si la flag principal está desactivada, no hacer nada más.
            return;
        }

        final PoseStack poseStack = event.getPoseStack();

        // Elige UN solo stage para probar. AFTER_PARTICLES o AFTER_SKY.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            //renderFixedTestLine_ConRenderType(event.getPoseStack());

            List<AcousticRay.VisualRay> raysToDraw = AcousticRayManager.getInstance().getVisualRaysToRender(mc.level.getGameTime());
            if (raysToDraw != null && !raysToDraw.isEmpty()) {
                //System.out.println("    Llamando a renderAcousticRays_ConTesselator con " + raysToDraw.size() + " rayos acústicos.");
                renderRays(event.getPoseStack(), raysToDraw);
            } else {
                //System.out.println("    No hay rayos acústicos reales para dibujar esta vez.");
            }

            if (renderTextureSlice) {
                renderTextureSliceForDebug(event.getPoseStack());
            }
        }

        if (renderCollisionPlanes && event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            //Minecraft mc = Minecraft.getInstance();
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 camPos = camera.getPosition();
            Vec3 playerPos = mc.player.position(); // Usar la posición del jugador para el origen de los planos

            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
            VertexConsumer lineBuffer = bufferSource.getBuffer(RenderType.lines());

            poseStack.pushPose();
            // Mover el sistema de coordenadas para que el origen (0,0,0) sea la posición de la cámara
            poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

            // Ahora, posicionar los planos en la ubicación del jugador
            poseStack.translate(playerPos.x, playerPos.y + mc.player.getEyeHeight(), playerPos.z);

            // Aplicar la rotación de la cámara para que los planos roten con el jugador
            poseStack.mulPose(camera.rotation());
            Matrix4f matrix = poseStack.last().pose();

            float half = dimensions / 2.0f;

            // Dibujar los tres planos ortogonales
            drawSquare(lineBuffer, matrix, Vec3.ZERO, half, 'Z'); // Plano XY (frontal)
            drawSquare(lineBuffer, matrix, Vec3.ZERO, half, 'Y'); // Plano XZ (superior/inferior)
            drawSquare(lineBuffer, matrix, Vec3.ZERO, half, 'X'); // Plano YZ (lateral)

            poseStack.popPose();
            bufferSource.endBatch(RenderType.lines());
        }
    }

    public static void renderTextureSliceForDebug(PoseStack poseStack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Obtenemos la textura y su origen de nuestra caché
        WorldTextureCache cache = WorldTextureCache.getInstance();
        int textureId = cache.getTextureId(mc.player);
        BlockPos textureOrigin = cache.getTextureOrigin();

        if (textureId == -1) return;

        // --- Preparamos los datos para dibujar ---
        List<Vec3> pointsToDraw = new ArrayList<>();
        // Vamos a dibujar una "rebanada" de la textura a la altura de los pies del jugador
        int playerY = mc.player.blockPosition().getY();
        int size = WorldTextureCache.TEXTURE_DIMENSION; // 128

        // Creamos una rejilla de puntos en el mundo que se corresponden con los vóxeles de la textura
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                // Coordenada del mundo que queremos probar
                BlockPos worldPos = textureOrigin.offset(x, playerY - textureOrigin.getY(), z);
                pointsToDraw.add(new Vec3(worldPos.getX() + 0.5, worldPos.getY() + 0.5, worldPos.getZ() + 0.5));
            }
        }

        // --- Renderizado con OpenGL ---
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Usamos nuestro nuevo shader de depuración
        glUseProgram(RayShaderHandler.debugTextureProgram);

        // Pasamos la matriz de proyección de la cámara
        Matrix4f projMatrix = RenderSystem.getProjectionMatrix();
        Matrix4f viewMatrix = poseStack.last().pose();
        Matrix4f mvpMatrix = new Matrix4f(projMatrix).mul(viewMatrix);

        int mvpLoc = glGetUniformLocation(RayShaderHandler.debugTextureProgram, "modelViewProjectionMatrix");
        FloatBuffer mvpBuffer = BufferUtils.createFloatBuffer(16);
        mvpMatrix.get(mvpBuffer);
        glUniformMatrix4fv(mvpLoc, false, mvpBuffer);

        // Pasamos la textura y su offset
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_3D, textureId);
        glUniform1i(glGetUniformLocation(RayShaderHandler.debugTextureProgram, "worldTexture"), 0);
        glUniform3i(glGetUniformLocation(RayShaderHandler.debugTextureProgram, "worldOffset"), textureOrigin.getX(), textureOrigin.getY(), textureOrigin.getZ());

        // --- Creamos un VBO y VAO sobre la marcha para dibujar los puntos ---
        FloatBuffer pointBuffer = BufferUtils.createFloatBuffer(pointsToDraw.size() * 3);
        for(Vec3 p : pointsToDraw) {
            pointBuffer.put((float)p.x).put((float)p.y).put((float)p.z);
        }
        pointBuffer.flip();

        int vbo = glGenBuffers();
        int vao = glGenVertexArrays();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, pointBuffer, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);

        // Dibujamos los puntos
        glDrawArrays(GL_POINTS, 0, pointsToDraw.size());

        // Limpieza
        glBindVertexArray(0);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glUseProgram(0);

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
        /*
        List<AcousticRay.VisualRay> raysToDraw = AcousticRayManager.getVisualRaysToRender(mc.level.getGameTime());
        if (raysToDraw != null && !raysToDraw.isEmpty()) {
            // renderAcousticRaysWithBufferBuilder(event.getPoseStack(), raysToDraw);
        }

        }

        // Tu lógica para renderCollisionPlanes puede permanecer aquí si funciona independientemente
        if (renderCollisionPlanes && event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // ... tu código de renderCollisionPlanes ...
            // Asegúrate de que también use la traslación -camPos y pase la matriz correcta
            // si drawSquare espera coordenadas del mundo y una matriz ModelView.
        }
    }

    /*@SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();


        //if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;

        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        long currentTick = mc.level.getGameTime();

        if (renderRays) {
            //renderRays(event.getPoseStack()); // <---- PASAR EL POSESTACK

            // Obtener los rayos visuales del AcousticRayManager
            // Asumimos que AcousticRayManager tiene un método estático para esto
            // o que tienes una instancia de AcousticRayManager.
            // Pasamos el currentTick si es necesario para la lógica de expiración/actualización de rayos.

            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) { // O AFTER_SKY
                List<AcousticRay.VisualRay> raysToDraw = AcousticRayManager.getVisualRaysToRender(Minecraft.getInstance().level.getGameTime());
                if (raysToDraw != null && !raysToDraw.isEmpty()) {
                    //System.out.println("[DEBUG] SoundDebugger.onRenderLevelStage: Llamando a renderRaysWithBufferBuilder con " + raysToDraw.size() + " rayos."); // ¿APARECE ESTE LOG?
                    renderRaysWithBufferBuilder(event.getPoseStack(), raysToDraw);
                } else {
                    //System.out.println("[DEBUG] SoundDebugger.onRenderLevelStage: No hay rayos para dibujar con BufferBuilder."); // ¿APARECE ESTE LOG?
                }
            }
        }

        /*if (renderRays && event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            Vec3 camPos     = mc.gameRenderer.getMainCamera().getPosition();
            long currentTick= mc.level.getGameTime();

            // 1) Haces snapshot de la lista de rays
            //List<AcousticRay> raysSnapshot = List.copyOf(AcousticRayManager.getActiveRays());

            for (var ray : visualRays) {
                // 2) Haces snapshot de los segmentos de este ray
                List<Vec3> segments = List.copyOf(ray.getPathSegments());
                float maxVisualDistance = ray.getPropagationSpeed() * (currentTick - ray.getTickLaunched()) / 20f;
                List<Integer> bounceIndices = ray.getBounceIndices();

                if (segments.size() == 1) {
                    if (renderRaysDebugMode) {
                        Vec3 p = segments.get(0);
                        renderRaySegment(poseStack, p, p.add(0.2, 0, 0), camPos, 0);
                    }
                    continue;
                }

                int localBounce = 0;
                float cumulative = 0;

                for (int i = 0; i < segments.size() - 1; i++) {
                    Vec3 from = segments.get(i);
                    Vec3 to   = segments.get(i + 1);

                    if (bounceIndices.contains(i)) localBounce++;

                    float segmentLength = (float) from.distanceTo(to);
                    if (cumulative + segmentLength > maxVisualDistance) break;

                    renderRaySegment(poseStack, from, to, camPos, localBounce);
                    cumulative += segmentLength;
                }
            }
        }


        if (renderSPL && event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            BlockPos playerPos = player.blockPosition();
            int chunkSize = 16;

            int chunkX = playerPos.getX() >> 4;
            int chunkZ = playerPos.getZ() >> 4;
            int chunkY = playerPos.getY() >> 4;

            for (int cx = chunkX - 1; cx <= chunkX + 1; cx++) {
                for (int cz = chunkZ - 1; cz <= chunkZ + 1; cz++) {
                    for (int cy = chunkY - 1; cy <= chunkY + 1; cy++) {
                        int minX = cx * chunkSize;
                        int minY = cy * chunkSize;
                        int minZ = cz * chunkSize;

                        for (int dx = 0; dx < chunkSize; dx++) {
                            for (int dy = 0; dy < chunkSize; dy++) {
                                for (int dz = 0; dz < chunkSize; dz++) {
                                    BlockPos pos = new BlockPos(minX + dx, minY + dy, minZ + dz);
                                    if (!level.getBlockState(pos).isAir()) continue;

                                    double spl = blockSPLMap.getOrDefault(pos, 0.0);
                                    if (spl == 0) continue;
                                    int color = getInterpolatedColor(spl);
                                    renderLabel(poseStack, buffer, pos, String.format("%.1f dB", spl), color);
                                }
                            }
                        }
                    }
                }
            }

            buffer.endBatch();
        }

        if (renderRays && event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
            long currentTick = mc.level.getGameTime();

            for (AcousticRay ray : visualRays) {
                List<Vec3> segments = ray.getPathSegments();
                float maxVisualDistance = ray.getPropagationSpeed() * (currentTick - ray.getTickLaunched()) / 20f;
                List<Integer> bounceIndices = ray.getBounceIndices();

                if (segments.size() == 1) {
                    if (renderRaysDebugMode) {
                        Vec3 p = segments.get(0);
                        renderRaySegment(poseStack, p, p.add(0.2, 0, 0), camPos, 0);
                    }
                    continue;
                }

                int localBounce = 0;

                for (int i = 0; i < segments.size() - 1; i++) {
                    Vec3 from = segments.get(i);
                    Vec3 to = segments.get(i + 1);

                    if (bounceIndices.contains(i)) {
                        localBounce++;
                    }

                    if (ray.getCumulativeDistanceTo(i + 1) > maxVisualDistance) break;

                    renderRaySegment(poseStack, from, to, camPos, localBounce);
                }
            }

            visualRays.removeIf(r -> currentTick > r.getExpireTick());

        }
        if (renderCollisionPlanes && event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            Vec3 playerPos = player.position();
            //Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();

            MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
            VertexConsumer lineBuffer = bufferSource.getBuffer(RenderType.LINES);

            // Ajustamos el PoseStack al espacio de la cámara
            poseStack.pushPose();
            // trasladamos al origen de la cámara
            poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
            // trasladamos al jugador
            poseStack.translate(playerPos.x, playerPos.y, playerPos.z);
            // rotación de la cámara: aplica la orientación de la cabeza del jugador en los 3 ejes
            poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            Matrix4f matrix = poseStack.last().pose();

            float half = dimensions / 2.0f;
            // Dibujamos los contornos de los tres planos en el espacio local del jugador
            drawSquare(lineBuffer, matrix, Vec3.ZERO, half, 'Z'); // plano local XY
            drawSquare(lineBuffer, matrix, Vec3.ZERO, half, 'Y'); // plano local XZ
            drawSquare(lineBuffer, matrix, Vec3.ZERO, half, 'X'); // plano local YZ
            poseStack.popPose();
            bufferSource.endBatch();
        }
    }*/

    /**
     * Renderiza una lista de rayos visuales usando el sistema BufferBuilder de Minecraft.
     * Este método debe ser llamado desde un contexto de renderizado donde la PoseStack
     * ya esté configurada con las transformaciones de cámara (ej. RenderLevelStageEvent).
     *
     * @param poseStack La PoseStack actual del evento de renderizado.
     * @param visualRaysToRender La lista de objetos VisualRay a dibujar.
     */
    /**
     * Renderiza una lista de AcousticRay.VisualRay usando el sistema BufferBuilder de Minecraft.
     * Este método debe ser llamado desde un contexto de renderizado de Minecraft donde la PoseStack
     * ya esté configurada con las transformaciones de cámara (ej. desde RenderLevelStageEvent).
     *
     * @param poseStack La PoseStack actual del evento de renderizado.
     * @param visualRaysToRender La lista de objetos VisualRay a dibujar. Cada VisualRay debe
     * tener un método getPathSegments() que devuelva List<Vec3>.
     */
    // MÉTODO PARA LOS RAYOS ACÚSTICOS REALES (MODIFICADO para usar RenderType.debugLineStrip)
    public static void renderRays(PoseStack poseStack, List<AcousticRay.VisualRay> visualRaysToRender) {
        if (visualRaysToRender == null || visualRaysToRender.isEmpty()) {
            return;
        }
        //System.out.println("[DEBUG] SoundDebugger: Entrando en renderAcousticRays_UsingRenderType con " + visualRaysToRender.size() + " rayos.");

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        // Usar RenderType.debugLineStrip() para que se comporte como LINE_STRIP.
        // El grosor se pasa al RenderType. 2.0f es un buen punto de partida.
        // ¡Cada LINE_STRIP independiente necesita su propio batch o ser gestionado cuidadosamente!
        // Una forma es obtener el buffer una vez si todos los strips se pueden concatenar
        // o si el RenderType maneja múltiples strips dentro de un solo begin/end implícito.
        // Para strips separados, es más seguro finalizar el batch después de cada strip,
        // lo que implica que MultiBufferSource manejará el reinicio del buffer para el mismo RenderType.
        RenderType rayRenderType = RenderType.debugLineStrip(2.0f);

        // Configuración de estados globales de RenderSystem
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest(); // Mantener para asegurar visibilidad

        // No necesitas RenderSystem.setShader() aquí, RenderType lo maneja.

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f matrix = poseStack.last().pose(); // Matriz ModelView

        int raysActuallyDrawn = 0;
        for (AcousticRay.VisualRay visualRay : visualRaysToRender) {
            assert Minecraft.getInstance().level != null;
            List<Vec3> pathSegments = visualRay.buildRenderPath(Minecraft.getInstance().level.getGameTime());

            if (pathSegments == null || pathSegments.size() < 2) {
                continue;
            }

            //System.out.println("  Procesando rayo acústico (RenderType) " + (raysActuallyDrawn + 1) + "/" + visualRaysToRender.size() + " con " + pathSegments.size() + " puntos. PrimerPunto: " + pathSegments.get(0));

            // Obtener el VertexConsumer PARA ESTE RAYO (dentro del bucle de rayos, antes del bucle de puntos)
            // Esto asegura que cada LINE_STRIP tenga la oportunidad de ser un nuevo batch si es necesario.
            VertexConsumer vertexConsumer = bufferSource.getBuffer(rayRenderType);

            // Color naranja semi-transparente (componentes 0-255 para .color(int,int,int,int))
            int r = 255;
            int g = (int)(0.2f * 255);
            int b = 0;
            int a = (int)(0.8f * 255);

            for (Vec3 point : pathSegments) { // No hay necesidad de un índice 'i' aquí
                if (point == null) continue;

                vertexConsumer.vertex(matrix, (float)point.x, (float)point.y, (float)point.z)
                        .color(r, g, b, a)
                        .normal(poseStack.last().normal(), 0f, 1f, 0f) // Normal dummy
                        .endVertex();
            }
            // Finalizar el batch para ESTE RenderType DESPUÉS de CADA rayo (LINE_STRIP)
            // Esto es importante porque RenderType.debugLineStrip() es un tipo específico.
            // Si no lo haces, todos los LINE_STRIPs se conectarán o podrías tener errores.
            bufferSource.endBatch(rayRenderType);
            //System.out.println("    bufferSource.endBatch() llamado para rayo acústico (RenderType) " + (raysActuallyDrawn + 1));
            raysActuallyDrawn++;
        }
        //System.out.println("[DEBUG] SoundDebugger: " + raysActuallyDrawn + " rayos acústicos dibujados con RenderType.");

        poseStack.popPose();

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        //System.out.println("[DEBUG] SoundDebugger: renderAcousticRays_UsingRenderType finalizado.");
    }

    public static void renderFixedTestLine_ConRenderType(PoseStack poseStack) {
        System.out.println("[DEBUG] SoundDebugger: Entrando en renderFixedTestLine (CON RenderType.LINES v2).");

        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        // RenderType.lines() establece su propio shader y estados.
        // Puede que no necesites llamar a RenderSystem.setShader() ni a muchos otros estados de RenderSystem.
        // Sin embargo, controlar el depth test globalmente aún puede ser útil.
        RenderSystem.disableDepthTest(); // Intenta mantener esto para asegurar la visibilidad.
        RenderSystem.disableCull();

        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z); // Compensar posición de la cámara

        // La matriz que se pasa a vertexConsumer.vertex() es la matriz de la PoseStack actual
        Matrix4f matrix = poseStack.last().pose();

        float x1=5,  y1=-20, z1=10;
        float x2=5, y2=-20, z2=25;
        int r=255, g=0, b=0, a=255; // Rojo opaco para RenderType (0-255)

        System.out.println("[DEBUG] SoundDebugger: Intentando dibujar LÍNEA DE PRUEBA FIJA con RenderType.LINES v2.");
        System.out.println("    Vértice 1 (mundo): (" + x1 + ", " + y1 + ", " + z1 + ")");
        vertexConsumer.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(poseStack.last().normal(), 0f, 1f, 0f).endVertex();

        System.out.println("    Vértice 2 (mundo): (" + x2 + ", " + y2 + ", " + z2 + ")");
        vertexConsumer.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(poseStack.last().normal(), 0f, 1f, 0f).endVertex();

        // ¡MUY IMPORTANTE! Debes llamar a endBatch para el buffer específico o para todos los buffers
        // cuando usas MultiBufferSource directamente.
        bufferSource.endBatch(RenderType.lines());
        // O simplemente bufferSource.endBatch(); si solo has usado este RenderType en este punto.
        System.out.println("[DEBUG] SoundDebugger: bufferSource.endBatch(RenderType.lines()) llamado.");

        poseStack.popPose();

        // Restaurar estados que cambiaste globalmente
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();

        System.out.println("[DEBUG] SoundDebugger: renderFixedTestLine (CON RenderType.LINES v2) finalizado.");
    }


    private static void renderLabel(PoseStack poseStack, MultiBufferSource buffer, BlockPos pos, String text, int color) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();

        double dx = pos.getX() + 0.5 - camPos.x;
        double dy = pos.getY() + 0.5 - camPos.y;
        double dz = pos.getZ() + 0.5 - camPos.z;

        poseStack.pushPose();
        poseStack.translate(dx, dy, dz);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.01F, -0.01F, 0.01F);

        Matrix4f matrix = poseStack.last().pose();
        float backgroundAlpha = 0.25f;
        float halfWidth = font.width(text) / 2f;

        font.drawInBatch(text, -halfWidth, 0, color, false, matrix, buffer, Font.DisplayMode.NORMAL, (int)(backgroundAlpha * 255) << 24, LightTexture.FULL_BRIGHT);

        poseStack.popPose();
    }

    private static int getInterpolatedColor(double spl) {
        double clampedSPL = Math.max(30, Math.min(130, spl));
        double[] dB =    {  30,  50,  70,  90, 110, 130 };
        int[][] colors = {
                {   0,   0, 255 },
                {   0, 255, 255 },
                {   0, 255,   0 },
                { 255, 255,   0 },
                { 255, 165,   0 },
                { 255,   0,   0 }
        };

        for (int i = 0; i < dB.length - 1; i++) {
            if (clampedSPL >= dB[i] && clampedSPL <= dB[i + 1]) {
                double ratio = (clampedSPL - dB[i]) / (dB[i + 1] - dB[i]);
                int r = (int) (colors[i][0] + ratio * (colors[i + 1][0] - colors[i][0]));
                int g = (int) (colors[i][1] + ratio * (colors[i + 1][1] - colors[i][1]));
                int b = (int) (colors[i][2] + ratio * (colors[i + 1][2] - colors[i][2]));

                int alpha = 0x88;
                return (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return 0x88FFFFFF;
    }


    private static void drawSquare(VertexConsumer buffer, Matrix4f matrix, Vec3 center, float half, char normalAxis) {
        Vec3[] corners = new Vec3[4];

        switch (normalAxis) {
            case 'X' -> {
                corners[0] = center.add(0, -half, -half);
                corners[1] = center.add(0, half, -half);
                corners[2] = center.add(0, half, half);
                corners[3] = center.add(0, -half, half);
            }
            case 'Y' -> {
                corners[0] = center.add(-half, 0, -half);
                corners[1] = center.add(half, 0, -half);
                corners[2] = center.add(half, 0, half);
                corners[3] = center.add(-half, 0, half);
            }
            case 'Z' -> {
                corners[0] = center.add(-half, -half, 0);
                corners[1] = center.add(half, -half, 0);
                corners[2] = center.add(half, half, 0);
                corners[3] = center.add(-half, half, 0);
            }
        }

        // Dibujar contorno claramente conectado
        for (int i = 0; i < 4; i++) {
            Vec3 start = corners[i];
            Vec3 end = corners[(i + 1) % 4];

            buffer.vertex(matrix, (float)start.x, (float)start.y, (float)start.z)
                    .color(0, 255, 0, 255)  // verde sólido como el Structure Block
                    .normal(0, 1, 0)
                    .endVertex();

            buffer.vertex(matrix, (float)end.x, (float)end.y, (float)end.z)
                    .color(0, 255, 0, 255)
                    .normal(0, 1, 0)
                    .endVertex();
        }
    }
}