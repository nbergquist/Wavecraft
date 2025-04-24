package com.nicholas.wavecraft.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.Sound;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Mod.EventBusSubscriber(modid = "wavecraft", value = Dist.CLIENT)
public class SoundDebugger {

    public record TrackedSound(ResourceLocation audioFile, Vec3 position, float volume, long tickAdded) {}
    public record QueuedSound(SoundInstance instance, long tickAdded) {}
    public static final List<QueuedSound> queuedSounds = new ArrayList<>();

    public static final List<TrackedSound> activeSounds = new ArrayList<>();
    private static long currentTick = 0;
    public static boolean renderSPL = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        currentTick++;

        // Limpiar sonidos viejos
        activeSounds.removeIf(s -> currentTick - s.tickAdded() > 40);

        // Intentar procesar sonidos encolados
        Iterator<QueuedSound> it = queuedSounds.iterator();
        while (it.hasNext()) {
            QueuedSound qs = it.next();
            Sound soundData = qs.instance().getSound();
            if (soundData == null) continue;

            ResourceLocation soundFile = soundData.getLocation();
            Vec3 pos = new Vec3(qs.instance().getX(), qs.instance().getY(), qs.instance().getZ());
            float volume = qs.instance().getVolume();

            System.out.println("[SoundDebugger] Registrado sonido: " + soundFile + " en " + pos + " con volumen " + volume);
            activeSounds.add(new TrackedSound(soundFile, pos, volume, currentTick));
            it.remove(); // ya procesado
        }
    }


    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null) return;

        System.out.println("[SoundDebugger] Evento capturado → encolando.");
        queuedSounds.add(new QueuedSound(sound, currentTick));
    }



    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!renderSPL || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;

        Level level = Minecraft.getInstance().level;
        if (level == null) return;

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();

        int renderRadius = 8; // bloques alrededor de la fuente

        for (TrackedSound sound : activeSounds) {
            BlockPos center = BlockPos.containing(sound.position());

            for (int dx = -renderRadius; dx <= renderRadius; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -renderRadius; dz <= renderRadius; dz++) {
                        BlockPos pos = center.offset(dx, dy, dz);

                        if (!level.getBlockState(pos).is(Blocks.AIR)) continue;

                        Vec3 blockPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                        double distanceSq = sound.position().distanceToSqr(blockPos);
                        if (distanceSq < 1) distanceSq = 1;

                        double referencePower = 1e-12;
                        double intensity = (sound.volume() * sound.volume()) / distanceSq;
                        double spl = 10 * Math.log10(intensity / referencePower);

                        if (spl > 30) {
                            renderLabel(poseStack, buffer, pos, String.format("%.1f dB", spl));
                        }
                    }
                }
            }
        }

        buffer.endBatch();
    }


    private static double getSPLAtBlock(BlockPos pos) {
        Vec3 blockPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        double totalIntensity = 0;
        double referencePower = 1e-12;

        for (TrackedSound sound : activeSounds) {
            double distanceSq = sound.position().distanceToSqr(blockPos);
            if (distanceSq < 1) distanceSq = 1;

            double intensity = (sound.volume() * sound.volume()) / distanceSq;
            totalIntensity += intensity;
        }

        if (totalIntensity == 0) return 0;
        return 10 * Math.log10(totalIntensity / referencePower);
    }

    private static void renderLabel(PoseStack poseStack, MultiBufferSource buffer, BlockPos pos, String text) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        double camX = mc.getCameraEntity().getX();
        double camY = mc.getCameraEntity().getY();
        double camZ = mc.getCameraEntity().getZ();

        float x = (float)(pos.getX() + 0.5 - camX);
        float y = (float)(pos.getY() + 0.5 - camY);
        float z = (float)(pos.getZ() + 0.5 - camZ);

        poseStack.pushPose();
        poseStack.translate(x, y, z);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.025f, -0.025f, 0.025f);

        font.drawInBatch(
                text,
                -font.width(text) / 2f,
                0,
                0xFFFFFF,
                false,
                poseStack.last().pose(),
                buffer,
                Font.DisplayMode.NORMAL,
                0,
                LightTexture.FULL_BRIGHT
        );

        poseStack.popPose();
    }
}
