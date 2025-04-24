package com.nicholas.wavecraft.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.*;

@Mod.EventBusSubscriber(modid = "wavecraft", value = Dist.CLIENT)
public class SoundDebugger {

    public record TrackedSound(ResourceLocation audioFile, Vec3 position, float volume, long tickAdded) {}
    public record QueuedSound(SoundInstance instance, long tickAdded) {}

    public static final List<QueuedSound> queuedSounds = new ArrayList<>();
    public static final List<TrackedSound> activeSounds = new ArrayList<>();
    private static final Map<BlockPos, Double> blockSPLMap = new HashMap<>();

    private static long currentTick = 0;
    public static boolean renderSPL = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;

        currentTick++;
        activeSounds.removeIf(s -> currentTick - s.tickAdded() > 40);

        updateSPLMap(mc.level, activeSounds);
        decaySPLValues();
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
        if (!renderSPL || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        LocalPlayer player = mc.player;
        if (player == null) return;

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
}