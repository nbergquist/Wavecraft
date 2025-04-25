package com.nicholas.wavecraft.debug;

import com.mojang.blaze3d.vertex.*;
import com.nicholas.wavecraft.sound.AcousticRayManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.VertexConsumer; // Importar desde Minecraft


import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;


import java.util.*;

@Mod.EventBusSubscriber(modid = "wavecraft", value = Dist.CLIENT)
public class SoundDebugger {

    public record TrackedSound(ResourceLocation audioFile, Vec3 position, float volume, long tickAdded) {}
    public record QueuedSound(SoundInstance instance, long tickAdded) {}

    public static final List<QueuedSound> queuedSounds = new ArrayList<>();
    public static final List<TrackedSound> activeSounds = new ArrayList<>();
    private static final Map<BlockPos, Double> blockSPLMap = new HashMap<>();

    public static long currentTick = 0;
    public static boolean renderSPL = false;

    public static boolean renderRays = false;
    public static boolean rayEmissionEnabled = true;
    public static boolean renderRaysDebugMode = false;

    public static boolean renderCollisionPlanes = false;
    public static float dimensions = 20.0f;


    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;

        currentTick++;

        activeSounds.removeIf(s -> currentTick - s.tickAdded() > 40);

        // Promover sonidos de la cola a la lista activa
        Iterator<QueuedSound> iterator = queuedSounds.iterator();
        while (iterator.hasNext()) {
            QueuedSound queued = iterator.next();
            SoundInstance sound = queued.instance();
            if (sound == null) continue;

            Vec3 pos = new Vec3(sound.getX(), sound.getY(), sound.getZ());

            // Puedes ignorar sonidos en el origen si quieres evitar falsos positivos
            if (pos.equals(Vec3.ZERO)) continue;

            float volume = sound.getVolume();
            if (volume <= 0) continue;

            activeSounds.add(new TrackedSound(sound.getLocation(), pos, volume, queued.tickAdded()));
            iterator.remove();
        }

        updateSPLMap(mc.level, activeSounds);
        decaySPLValues();

        AcousticRayManager.tick(mc.level, currentTick);

    }



    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null) return;

        queuedSounds.add(new QueuedSound(sound, currentTick));
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

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        LocalPlayer player = mc.player;
        if (player == null) return;

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

            for (var ray : AcousticRayManager.getActiveRays()) {
                List<Vec3> segments = ray.getPathSegments();
                float maxDistance = ray.getDistanceTraveled(currentTick);
                float cumulative = 0;

                if (segments.size() == 1) {
                    if (SoundDebugger.renderRaysDebugMode) {
                        Vec3 p = segments.get(0);
                        renderRaySegment(poseStack, p, p.add(0.2, 0, 0), camPos);
                    }
                    continue;
                }

                for (int i = 0; i < segments.size() - 1; i++) {
                    Vec3 from = segments.get(i);
                    Vec3 to = segments.get(i + 1);
                    float segmentLength = (float) from.distanceTo(to);

                    if (cumulative + segmentLength > maxDistance) break;

                    renderRaySegment(poseStack, from, to, camPos);
                    cumulative += segmentLength;
                }
            }
        }
        if (renderCollisionPlanes && event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            Vec3 playerPos = player.position();
            Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();

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
    }

    private static void renderRaySegment(PoseStack poseStack, Vec3 start, Vec3 end, Vec3 camPos) {
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer buffer = bufferSource.getBuffer(RenderType.LINES);

        double sx = start.x - camPos.x;
        double sy = start.y - camPos.y;
        double sz = start.z - camPos.z;

        double ex = end.x - camPos.x;
        double ey = end.y - camPos.y;
        double ez = end.z - camPos.z;

        Matrix4f matrix = poseStack.last().pose();

        buffer.vertex(matrix, (float)sx, (float)sy, (float)sz)
                .color(255, 0, 0, 255)
                .normal(0, 1, 0) // dummy normal
                .endVertex();
        buffer.vertex(matrix, (float)ex, (float)ey, (float)ez)
                .color(255, 0, 0, 255)
                .normal(0, 1, 0)
                .endVertex();
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