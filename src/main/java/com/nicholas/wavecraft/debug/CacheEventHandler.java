package com.nicholas.wavecraft.debug;

import com.nicholas.wavecraft.sound.WorldTextureCache;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "wavecraft", value = Dist.CLIENT)
public class CacheEventHandler {

    private static ChunkPos lastPlayerChunkPos = null;

    /**
     * Se ejecuta cada tick del cliente. Lo usaremos para detectar si el jugador ha cambiado de chunk.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ChunkPos currentPlayerChunkPos = mc.player.chunkPosition();

        if (lastPlayerChunkPos == null || !lastPlayerChunkPos.equals(currentPlayerChunkPos)) {
            // El jugador ha cambiado de chunk, hay que invalidar la caché
            WorldTextureCache.getInstance().invalidate();
            lastPlayerChunkPos = currentPlayerChunkPos;
        }
    }

    /**
     * Se ejecuta cuando un bloque es modificado por cualquier entidad (jugador, explosión, etc.).
     */
    @SubscribeEvent
    public static void onBlockChanged(BlockEvent.NeighborNotifyEvent event) {
        // Este evento se dispara mucho, pero es una forma robusta de detectar cambios.
        // Podríamos añadir una comprobación para ver si el bloque está dentro del volumen
        // de la textura, pero para empezar, invalidar siempre es seguro.
        WorldTextureCache.getInstance().invalidate();
    }
}