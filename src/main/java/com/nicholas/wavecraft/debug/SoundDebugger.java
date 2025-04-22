package com.nicholas.wavecraft.debug;

import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

@Mod.EventBusSubscriber(modid = "wavecraft", value = Dist.CLIENT)
public class SoundDebugger {

    public static boolean renderSPL = false;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!renderSPL || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;

        Level level = Minecraft.getInstance().level;
        if (level == null) return;

        BlockPos playerPos = Minecraft.getInstance().player.blockPosition();
        int chunkRadius = 3;

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = Minecraft.getInstance().renderBuffers().bufferSource();

        for (int dx = -chunkRadius * 16; dx < chunkRadius * 16; dx++) {
            for (int dy = -32; dy < 32; dy++) {
                for (int dz = -chunkRadius * 16; dz < chunkRadius * 16; dz++) {
                    BlockPos pos = playerPos.offset(dx, dy, dz);
                    if (level.getBlockState(pos).is(Blocks.AIR)) {
                        double spl = getSPLAtBlock(pos, level);
                        if (spl > 0) {
                            renderLabel(poseStack, buffer, pos, String.format("%.1f dB", spl));
                            //renderLabel(Minecraft.getInstance().player.blockPosition(), "TEST");

                        }
                    }
                }
            }
        }

        buffer.endBatch(RenderType.text(new ResourceLocation("minecraft", "textures/font/ascii.png")));


    }

    private static double getSPLAtBlock(BlockPos pos, Level level) {
        // TODO: Implementa tu cálculo aquí. Por ahora, devuelve un ejemplo:
        return Math.random() < 0.005 ? 60 + Math.random() * 30 : 0;
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
        poseStack.scale(0.02f, 0.02f, 0.02f);  // Escala para que no sea gigante

        Quaternionf rotation = mc.getEntityRenderDispatcher().cameraOrientation();
        poseStack.mulPose(rotation);
        poseStack.mulPose(Axis.YP.rotationDegrees(180)); // Para que no se vea al revés

        font.drawInBatch(
                text,
                -font.width(text) / 2f,
                0,
                0xFFFFFF,         // color blanco
                false,
                poseStack.last().pose(),
                buffer,
                Font.DisplayMode.NORMAL,
                0,
                LightTexture.FULL_BRIGHT
        );

        poseStack.popPose();
    }
    /*private static void renderLabel(BlockPos pos, String text) {
        Minecraft mc = Minecraft.getInstance();

        double x = pos.getX() + 0.5;
        double y = pos.getY() + 2.0;
        double z = pos.getZ() + 0.5;

        PoseStack poseStack = new PoseStack();
        poseStack.translate(
                x - mc.gameRenderer.getMainCamera().getPosition().x,
                y - mc.gameRenderer.getMainCamera().getPosition().y,
                z - mc.gameRenderer.getMainCamera().getPosition().z
        );

        Matrix4f matrix = poseStack.last().pose();

        mc.font.drawInBatch(
                Component.literal(text),
                0, 0,              // x e y relativos al poseStack
                0xFFFFFF,          // color blanco
                false,             // sin sombra
                matrix,
                mc.renderBuffers().bufferSource(),
                Font.DisplayMode.NORMAL,
                0,                 // fondo transparente
                15728880           // FULL_BRIGHT
        );

        mc.renderBuffers().bufferSource().endBatch();
    }*/
}