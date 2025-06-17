package com.nicholas.wavecraft.sound;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class WorldTextureCache {

    // --- Singleton Pattern ---
    private static final WorldTextureCache INSTANCE = new WorldTextureCache();
    public static WorldTextureCache getInstance() {
        return INSTANCE;
    }

    // --- Constantes y Campos de la Caché ---
    public static final int TEXTURE_DIMENSION = 128; // El tamaño de nuestro volumen: 128x128x128

    private int textureId = -1; // El ID de OpenGL para nuestra textura
    private BlockPos textureOrigin = null; // La coordenada del mundo que corresponde al (0,0,0) de nuestra textura
    private boolean isDirty = true; // Un flag para marcar si la textura necesita ser regenerada

    private WorldTextureCache() {} // Constructor privado para el Singleton

    /**
     * Marca la caché como "sucia". La próxima vez que se pida la textura, se regenerará.
     * Esto será llamado por el CacheEventHandler cuando un bloque cambie o el jugador se mueva de chunk.
     */
    public void invalidate() {
        // Para evitar spam en la consola, solo imprimimos si el estado realmente cambia.
        if (!this.isDirty) {
            this.isDirty = true;
            System.out.println("[Wavecraft Cache] La caché ha sido invalidada. Se regenerará en el próximo uso.");
        }
    }

    /**
     * El método principal que usará el RayShaderHandler.
     * Devuelve el ID de la textura 3D válida para la posición actual del jugador.
     * Si la caché está sucia (marcada por invalidate()) o no ha sido creada, la regenera.
     * @param player El jugador actual, para centrar la textura.
     * @return El ID de la textura de OpenGL.
     */
    public int getTextureId(LocalPlayer player) {
        if (this.isDirty || this.textureId == -1) {
            regenerateTexture(player);
        }
        return this.textureId;
    }

    /**
     * Devuelve el origen en el mundo de la textura actual para usarlo como 'worldOffset' en el shader.
     */
    public BlockPos getTextureOrigin() {
        return this.textureOrigin;
    }

    /**
     * El trabajo pesado: borra la textura antigua (si existe) y crea una nueva.
     * @param player El jugador actual, usado para obtener el nivel y la posición.
     */
    private void regenerateTexture(LocalPlayer player) {
        // Como este método se llama desde el hilo de render, podemos ejecutar comandos de OpenGL directamente.
        this.textureOrigin = player.blockPosition().offset(-TEXTURE_DIMENSION / 2, -TEXTURE_DIMENSION / 2, -TEXTURE_DIMENSION / 2);
        System.out.println("[Wavecraft Cache] Regenerando textura en origen: " + this.textureOrigin);

        // 1. Borra la textura antigua si existe
        if (this.textureId != -1) {
            GL33.glDeleteTextures(this.textureId);
            this.textureId = -1; // Marcar ID como inválida
        }

        // 2. Genera los datos de la textura en la CPU
        byte[] rgbaData = generateTextureData(player.level(), this.textureOrigin, TEXTURE_DIMENSION, TEXTURE_DIMENSION, TEXTURE_DIMENSION);
        ByteBuffer buffer = null;
        try {
            buffer = MemoryUtil.memAlloc(rgbaData.length);
            buffer.put(rgbaData).flip();

            // 3. Genera, configura y sube la nueva textura en un bloque síncrono
            textureId = GL33.glGenTextures();
            GL33.glBindTexture(GL33.GL_TEXTURE_3D, textureId);

            // Configurar parámetros para evitar wrapping y usar el filtrado más simple (más rápido)
            GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);

            // Usar glTexImage3D para asignar memoria y subir los datos a la vez
            GL12.glTexImage3D(
                    GL12.GL_TEXTURE_3D,
                    0,
                    GL11.GL_RGBA8,          // Formato interno en la GPU
                    TEXTURE_DIMENSION, TEXTURE_DIMENSION, TEXTURE_DIMENSION,
                    0,
                    GL11.GL_RGBA,           // Formato del buffer que estamos subiendo (corregido)
                    GL11.GL_UNSIGNED_BYTE,  // Tipo de datos en el buffer
                    buffer
            );

            GL33.glBindTexture(GL33.GL_TEXTURE_3D, 0); // Desvincular para buena práctica
            System.out.println("[Wavecraft Cache] Textura con ID " + textureId + " regenerada y subida correctamente.");

        } finally {
            if (buffer != null) {
                MemoryUtil.memFree(buffer);
            }
        }

        // 4. Marcar como "limpia" SÓLO después de que la subida se ha completado
        this.isDirty = false;
    }

    /**
     * Crea un array de bytes representando la geometría del mundo y lo sube a la GPU como una textura 3D.
     */
    private int uploadBlockTexture3D(Level level, BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        System.out.println("[Wavecraft Cache] Llenando buffer de textura de " + sizeX + "x" + sizeY + "x" + sizeZ + "...");
        byte[] rgbaData = new byte[sizeX * sizeY * sizeZ * 4];

        int solidCount = 0;

        // Recorre cada punto en el volumen de la textura para muestrear el mundo.
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockPos worldPos = origin.offset(x, y, z);
                    boolean isSolid = !level.getBlockState(worldPos).isAir();

                    // --- ¡LA FÓRMULA DE INDEXACIÓN CORRECTA! ---
                    // Esto asegura que el orden de los datos en la CPU coincida con cómo OpenGL los lee.
                    int index = (z * sizeX * sizeY) + (y * sizeX) + x;

                    // Asigna el color. Sólido = Rojo (255,0,0,255), Aire = Negro (0,0,0,255).
                    // El shader solo se fija en el componente rojo (el primero).
                    if (isSolid) {
                        rgbaData[index * 4] = (byte) 255;       // R
                        rgbaData[index * 4 + 1] = 0;             // G
                        rgbaData[index * 4 + 2] = 0;             // B
                        rgbaData[index * 4 + 3] = (byte) 255;   // A

                        solidCount++;
                    } else {
                        rgbaData[index * 4] = 0;
                        rgbaData[index * 4 + 1] = 0;
                        rgbaData[index * 4 + 2] = 0;
                        rgbaData[index * 4 + 3] = (byte) 255;
                    }
                }
            }
        }
        System.out.println("[Wavecraft Cache] Buffer de textura lleno. Subiendo a la GPU...");
        System.out.println("[DEBUG] Bloques sólidos en textura: " + solidCount);

        // Genera y configura la textura en OpenGL.
        int texID = GL33.glGenTextures();
        GL33.glBindTexture(GL33.GL_TEXTURE_3D, texID);

        // Parámetros para asegurar que la textura no se repita y se lea píxel por píxel.
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_WRAP_S, GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_WRAP_T, GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_WRAP_R, GL33.GL_CLAMP_TO_EDGE);
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);


        ByteBuffer buffer = ShaderHelper.createByteBuffer(rgbaData);
        System.out.println("[DEBUG] glTexImage3D PRUEBA - datos:");
        System.out.println(" - sizeX = " + sizeX);
        System.out.println(" - sizeY = " + sizeY);
        System.out.println(" - sizeZ = " + sizeZ);
        System.out.println(" - rgbaData.length = " + rgbaData.length);
        System.out.println(" - buffer.capacity() = " + buffer.capacity());
        System.out.println(" - buffer.isDirect() = " + buffer.isDirect());

        try {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL33.glTexImage3D(
                    GL33.GL_TEXTURE_3D, 0, GL33.GL_RGBA8,
                    sizeX, sizeY, sizeZ, 0,
                    GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE,
                    buffer
            );
            System.out.println("[DEBUG] ¡Textura subida con éxito!");
        } catch (Throwable t) {
            System.err.println("[CRITICAL] CRASH en glTexImage3D: " + t);
            t.printStackTrace();
        }
        MemoryUtil.memFree(buffer);


        GL33.glBindTexture(GL33.GL_TEXTURE_3D, 0);

        System.out.println("[Wavecraft Cache] Textura con ID " + texID + " subida correctamente.");
        return texID;
    }

    private static byte[] generateTextureData(Level level, BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        byte[] data = new byte[sizeX * sizeY * sizeZ * 4]; // RGBA
        int i = 0;

        for (int z = 0; z < sizeZ; z++) {
            for (int y = 0; y < sizeY; y++) {
                for (int x = 0; x < sizeX; x++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    // Ejemplo: gris claro para aire, blanco para bloques sólidos
                    if (state.isAir()) {
                        data[i++] = (byte) 64;  // R
                        data[i++] = (byte) 64;  // G
                        data[i++] = (byte) 64;  // B
                        data[i++] = (byte) 64;  // A
                    } else {
                        data[i++] = (byte) 255;
                        data[i++] = (byte) 255;
                        data[i++] = (byte) 255;
                        data[i++] = (byte) 255;
                    }
                }
            }
        }

        return data;
    }
}