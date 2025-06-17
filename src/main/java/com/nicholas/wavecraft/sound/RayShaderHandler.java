package com.nicholas.wavecraft.sound;

import com.nicholas.wavecraft.config.WavecraftConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

// RayShaderHandler.java
public class RayShaderHandler {
    public static int shaderProgram = -1;       // Para el cálculo
    public static int renderProgram;  // Shader para visualizar los rayos
    public static int debugTextureProgram = -1;

    public static int positionVBO = -1;
    public static int dummyVAO = -1;
    public static int renderVAO = -1;
    public static int numVertices = 0;

    public static int currentMaxBounces = 20;

    // --- NUEVOS CAMPOS PARA GUARDAR LAS LOCATIONS ---
    private static int rayOrigin_loc = -1;
    private static int rayDirection_loc = -1;
    private static int speed_loc = -1;
    private static int maxBounces_loc = -1;
    private static int worldTexture_loc = -1;
    private static int worldOffset_loc = -1;

    public static void setUniform3f(String name, float x, float y, float z) {
        int loc = GL20.glGetUniformLocation(shaderProgram, name);
        if (loc != -1) {
            GL20.glUniform3f(loc, x, y, z);
        }
    }

    public static void setUniform1i(String name, int value) {
        int loc = GL20.glGetUniformLocation(shaderProgram, name);
        if (loc != -1) {
            GL20.glUniform1i(loc, value);
        }
    }

    public static void setUniform1f(String name, float value) {
        int loc = GL20.glGetUniformLocation(shaderProgram, name);
        if (loc != -1) {
            GL20.glUniform1f(loc, value);
        }
    }

    public static void setUniformVec3(String name, Vec3 vec) {
        int location = GL20.glGetUniformLocation(shaderProgram, name);
        GL20.glUniform3f(location, (float) vec.x, (float) vec.y, (float) vec.z);
    }

    public static void setUniformInt(String name, int value) {
        int location = GL20.glGetUniformLocation(shaderProgram, name);
        GL20.glUniform1i(location, value);
    }

    public static int getCurrentNumPoints() {
        return currentMaxBounces + 1;
    }

    public static void init() {
        System.out.println("[DEBUG] RayShaderHandler.init() called.");
        int vertexShader = ShaderHelper.loadShader(GL20.GL_VERTEX_SHADER, "ray_vertex.glsl");
        int fragmentShader = ShaderHelper.loadShader(GL20.GL_FRAGMENT_SHADER, "ray_fragment.glsl");

        shaderProgram = GL20.glCreateProgram();
        GL20.glAttachShader(shaderProgram, vertexShader);
        GL20.glAttachShader(shaderProgram, fragmentShader);

        String[] varyings = {
                "outPosition", "bounceStatus", "outNormal", "debugCoord_tex", "debugCode",
                "outRayOrigin", "outRayDirection", "outHitPointBeforeEpsilon",
                "outHitBlockCenter", "outAccumulatedT"
        };
        glTransformFeedbackVaryings(shaderProgram, varyings, GL_INTERLEAVED_ATTRIBS);
        GL20.glLinkProgram(shaderProgram);

        if (GL20.glGetProgrami(shaderProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(shaderProgram);
            throw new RuntimeException("TF Shader linking failed: " + log);
        }
        System.out.println("[DEBUG] RayShaderHandler: TF Shader (shaderProgram " + shaderProgram + ") linked successfully.");

        // ─── Ver TF varyings ───
        int tfCount = GL30.glGetProgrami(shaderProgram, GL30.GL_TRANSFORM_FEEDBACK_VARYINGS);
        System.out.println("[DEBUG] TF VARYINGS count = " + tfCount);

        for (int i = 0; i < tfCount; i++) {
            int bufSize = 256;
            ByteBuffer nameBuffer = BufferUtils.createByteBuffer(bufSize);
            int[] length = new int[1]; // 👈 ¡Este es el cambio clave!
            int[] size = new int[1];
            int[] type = new int[1];

            GL30.glGetTransformFeedbackVarying(
                    shaderProgram,
                    i,
                    length,
                    size,
                    type,
                    nameBuffer
            );

            byte[] nameBytes = new byte[length[0]];
            nameBuffer.get(nameBytes);
            String name = new String(nameBytes).trim();

            System.out.println("[DEBUG] TF VARYING[" + i + "] = " + name +
                    " (size=" + size[0] + ", type=0x" + Integer.toHexString(type[0]) + ")");
        }


        // Obtener y guardar las locations de los uniforms
        glUseProgram(shaderProgram);
        rayOrigin_loc = glGetUniformLocation(shaderProgram, "rayOrigin");
        rayDirection_loc = glGetUniformLocation(shaderProgram, "rayDirection");
        speed_loc = glGetUniformLocation(shaderProgram, "speed"); // Aunque no lo uses, es bueno tenerlo
        maxBounces_loc = glGetUniformLocation(shaderProgram, "maxBounces"); // No está en el shader, pero lo dejamos por si acaso
        worldTexture_loc = glGetUniformLocation(shaderProgram, "worldTexture");
        worldOffset_loc = glGetUniformLocation(shaderProgram, "worldOffset");
        glUseProgram(0);

        // Limpiar shaders después del enlace
        GL20.glDetachShader(shaderProgram, vertexShader);
        GL20.glDetachShader(shaderProgram, fragmentShader);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        /*int maxVertices = currentMaxBounces + 1;
        positionVBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionVBO);
        GL15.glBufferData(
                GL15.GL_ARRAY_BUFFER,
                maxVertices * 24L * Float.BYTES,
                GL15.GL_DYNAMIC_DRAW
        );
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);*/

        // <-- CORRECCIÓN: Se elimina la llamada a initBuffers(). Su lógica se integra en initRenderProgram().
        initRenderProgram(); // Inicializa VBO y VAO de renderizado
        initDebugTextureProgram();

        System.out.println("[DEBUG] RayShaderHandler.init() finished successfully.");
    }

    /*public static void initBuffers(int maxVertices) {
        renderVAO = GL30.glGenVertexArrays();          // ← un único VAO
        GL30.glBindVertexArray(renderVAO);

        positionVBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
                maxVertices * 24L * Float.BYTES,
                GL15.GL_DYNAMIC_DRAW);

        // atributo 0 = vec3 posición
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 1, GL11.GL_FLOAT, false, 0, 0);
        GL30.glBindVertexArray(0);
    }*/

    /**
     * Inicializa los recursos para VISUALIZAR los rayos.
     * Es la única fuente de verdad para 'positionVBO' y 'renderVAO'.
     */
    public static void initRenderProgram() {
        System.out.println("[DEBUG] RayShaderHandler.initRenderProgram() llamado.");
        // Asumiendo que ShaderHelper.loadShader es robusto y lanza excepciones en error
        int visVertexShader = ShaderHelper.loadShader(GL20.GL_VERTEX_SHADER, "ray_vis_vertex.glsl");
        int visFragmentShader = ShaderHelper.loadShader(GL20.GL_FRAGMENT_SHADER, "ray_vis_fragment.glsl");
        System.out.println("[DEBUG] RayShaderHandler: Shaders de visualización cargados (VS ID: " + visVertexShader + ", FS ID: " + visFragmentShader + ")");

        renderProgram = GL20.glCreateProgram();
        GL20.glAttachShader(renderProgram, visVertexShader);
        GL20.glAttachShader(renderProgram, visFragmentShader);
        GL20.glLinkProgram(renderProgram);

        if (GL20.glGetProgrami(renderProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(renderProgram);
            System.err.println("[CRITICAL_ERROR] Visualisation Shader (renderProgram) linking failed: " + log);
            GL20.glDeleteProgram(renderProgram);
            GL20.glDeleteShader(visVertexShader);
            GL20.glDeleteShader(visFragmentShader);
            renderProgram = -1; // Marcar como inválido
            return; // Salir si falla el enlace
        }
        System.out.println("[DEBUG] RayShaderHandler: Visualisation Shader (renderProgram ID " + renderProgram + ") linked successfully.");
        GL20.glDetachShader(renderProgram, visVertexShader);
        GL20.glDetachShader(renderProgram, visFragmentShader);
        GL20.glDeleteShader(visVertexShader);
        GL20.glDeleteShader(visFragmentShader);

        positionVBO = glGenBuffers();
        glBindBuffer(GL15.GL_ARRAY_BUFFER, positionVBO);
        glBufferData(GL15.GL_ARRAY_BUFFER, 1000 * 24L * Float.BYTES, GL15.GL_DYNAMIC_DRAW);
        //glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); // Desvincular para seguridad

        renderVAO = glGenVertexArrays();
        glBindVertexArray(renderVAO);
        System.out.println("[DEBUG] RayShaderHandler: renderVAO (ID " + renderVAO + ") generado y bindeado.");

        //GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, positionVBO);
        //System.out.println("[DEBUG] RayShaderHandler: positionVBO (ID " + positionVBO + ") bindeado a GL_ARRAY_BUFFER.");

        // Atributo 0: vec3 pos
        // 3 componentes de tipo GL_FLOAT
        // Normalizado: false
        // Stride: 3 * sizeof(float) = 12 bytes (distancia en bytes entre inicios de vértices consecutivos)
        // Offset: 0 (byte offset del primer componente del primer atributo)
        // Atributo 0: outPosition (vec3)
        long stride = 24 * Float.BYTES;

        glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, (int)stride, 0);                 // outPosition (vec3)
        System.out.println("[DEBUG] RayShaderHandler: glVertexAttribPointer para atributo 0 configurado.");
        glEnableVertexAttribArray(0);
        System.out.println("[DEBUG] RayShaderHandler: Atributo 0 habilitado para renderVAO.");

        // Atributo 1: bounceStatus (float)
        glVertexAttribPointer(1, 1, GL11.GL_FLOAT, false, (int)stride, 3 * Float.BYTES);  // bounceStatus (float)
        glEnableVertexAttribArray(1);
        System.out.println("[DEBUG] Atributo 1 (status) habilitado para renderVAO.");

        // Opcional – Atributo 2: outNormal (vec3)
        glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, (int)stride, 4 * Float.BYTES);
        glEnableVertexAttribArray(2);
        System.out.println("[DEBUG] Atributo 2 (normal) habilitado para renderVAO.");

        // Opcional – Atributo 3: debugCoord_tex (vec3)
        glVertexAttribPointer(3, 3, GL11.GL_FLOAT, false, (int)stride, 7 * Float.BYTES);
        glEnableVertexAttribArray(3);
        System.out.println("[DEBUG] Atributo 3 (debugCoord_tex) habilitado para renderVAO.");

        // Atributo 4: debugCode (float)
        glVertexAttribPointer(4, 1, GL11.GL_FLOAT, false, (int)stride, 10 * Float.BYTES);
        glEnableVertexAttribArray(4);

        // Atributo 5: outRayOrigin (vec3)
        glVertexAttribPointer(5, 3, GL11.GL_FLOAT, false, (int)stride, 11 * Float.BYTES);
        glEnableVertexAttribArray(5);

        // Atributo 6: outRayDirection (vec3)
        glVertexAttribPointer(6, 3, GL11.GL_FLOAT, false, (int)stride, 14 * Float.BYTES);
        glEnableVertexAttribArray(6);

        // Atributo 7: outHitPointBeforeEpsilon (vec3)
        glVertexAttribPointer(7, 3, GL11.GL_FLOAT, false, (int)stride, 17 * Float.BYTES);
        glEnableVertexAttribArray(7);

        // Atributo 8: outHitBlockCenter (vec3)
        glVertexAttribPointer(8, 3, GL11.GL_FLOAT, false, (int)stride, 20 * Float.BYTES);
        glEnableVertexAttribArray(8);

        // Atributo 9: outAccumulatedT (float)
        glVertexAttribPointer(9, 1, GL11.GL_FLOAT, false, (int)stride, 23 * Float.BYTES);
        glEnableVertexAttribArray(9);

        // Habilitar todos los atributos
        for (int i=0; i<=9; ++i) {
            GL20.glEnableVertexAttribArray(i);
        }

        // Cleanup
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        System.out.println("[DEBUG] RayShaderHandler: VAO y VBO desvinculados. renderVAO configuración completa.");
    }

    public static void initDebugTextureProgram() {
        System.out.println("[Wavecraft] Inicializando shader de depuración de textura...");
        int vertexShader = ShaderHelper.loadShader(GL_VERTEX_SHADER, "debug_texture_vertex.glsl");
        int fragmentShader = ShaderHelper.loadShader(GL_FRAGMENT_SHADER, "debug_texture_fragment.glsl");

        debugTextureProgram = glCreateProgram();
        glAttachShader(debugTextureProgram, vertexShader);
        glAttachShader(debugTextureProgram, fragmentShader);
        glLinkProgram(debugTextureProgram);

        if (glGetProgrami(debugTextureProgram, GL_LINK_STATUS) == GL_FALSE) {
            throw new RuntimeException("Debug Texture Shader linking failed: " + glGetProgramInfoLog(debugTextureProgram));
        }

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        System.out.println("[Wavecraft] Shader de depuración de textura cargado correctamente (ID: " + debugTextureProgram + ")");
    }

    public static void use() {
        GL20.glUseProgram(shaderProgram);
    }


    public static List<Vec3> calculateRayPath(Level level, LocalPlayer player, Vec3 origin, Vec3 direction, float speed, int maxBounces) {
        System.out.println("[DEBUG] Thread in calculateRayPath: " + Thread.currentThread().getName());
        //maxBounces = 4;
        System.out.println("[DEBUG] calculateRayPath maxBounces = " + maxBounces);
        int localDummyVAO = -1;
        List<Vec3> trajectory = new ArrayList<>(); // Declara trajectory aquí para que esté en el scope del return del try y del catch/finally (aunque en catch devuelves una nueva)

        int dummyVBO = -1;
        try {
            // 1. OBTENER DATOS DEL MUNDO
            WorldTextureCache cache = WorldTextureCache.getInstance();
            int textureId = cache.getTextureId(player);
            BlockPos textureOrigin = cache.getTextureOrigin();
            if (textureId == -1 || !glIsTexture(textureId) || positionVBO == -1) {
                return trajectory; // Salida segura si los recursos no están listos
            }

            int numVertices = maxBounces + 1;

            // 2. CONFIGURAR ESTADO DE OPENGL
            glUseProgram(shaderProgram);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_3D, textureId);

            // 3. PASAR UNIFORMS
            glUniform1i(worldTexture_loc, 0);
            GL20.glUniform3i(
                    worldOffset_loc,
                    textureOrigin.getX(),
                    textureOrigin.getY(),
                    textureOrigin.getZ()
            );
            glUniform3f(rayOrigin_loc, (float) origin.x, (float) origin.y, (float) origin.z);
            glUniform3f(rayDirection_loc, (float) direction.x, (float) direction.y, (float) direction.z);

            // 4. --- LA CORRECCIÓN CLAVE: VAO y VBO LOCALES Y LIMPIOS ---
            localDummyVAO = glGenVertexArrays();
            glBindVertexArray(localDummyVAO);

            glDisableVertexAttribArray(0);
            glVertexAttrib1f(0, 0.0f);
            // En este punto, el VAO activo está perfectamente alineado con el shader de cálculo.

            // 5. EJECUTAR TRANSFORM FEEDBACK
            glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, positionVBO);
            glEnable(GL_RASTERIZER_DISCARD);
            glBeginTransformFeedback(GL_POINTS);
            glDrawArrays(GL_POINTS, 0, numVertices);
            glEndTransformFeedback();
            glDisable(GL_RASTERIZER_DISCARD);

            // 6. LEER RESULTADOS
            FloatBuffer buffer = BufferUtils.createFloatBuffer(numVertices * 24);
            glBindBuffer(GL_ARRAY_BUFFER, positionVBO);
            glGetBufferSubData(GL_ARRAY_BUFFER, 0, buffer);
            buffer.rewind();

            if (WavecraftConfig.DEBUG_SHADER_OUTPUT.get()) {
                int stride = 24;
                for (int i = 0; i < numVertices; i++) {
                    int base = i * stride;
                    float px = buffer.get(base);
                    float py = buffer.get(base + 1);
                    float pz = buffer.get(base + 2);
                    float bounceStatus = buffer.get(base + 3);

                    float normX = buffer.get(base + 4);
                    float normY = buffer.get(base + 5);
                    float normZ = buffer.get(base + 6);

                    float tx = buffer.get(base + 7);
                    float ty = buffer.get(base + 8);
                    float tz = buffer.get(base + 9);

                    float codeA = buffer.get(base + 10);
                    float codeB = buffer.get(base + 11);

                    System.out.printf("Punto %d:%n", i);
                    System.out.printf("  Posición:                (%.2f, %.2f, %.2f)%n", px, py, pz);
                    System.out.printf("  Estado (bounceStatus):   %.1f%n", bounceStatus);
                    System.out.printf("  Normal del rebote:       (%.2f, %.2f, %.2f)%n", normX, normY, normZ);
                    System.out.printf("  Coord textura (bloque):  (%.2f, %.2f, %.2f)%n", tx, ty, tz);
                    System.out.printf("  Código debug:            %.1f, %.1f%n", codeA, codeB);
                }
            }

            // 7. PROCESAR TRAYECTORIA
            for (int i = 0; i < numVertices; i++) {
                float px = buffer.get(), py = buffer.get(), pz = buffer.get();
                if (px != 0 || py != 0 || pz != 0) { // Añadir solo puntos válidos
                    trajectory.add(new Vec3(px, py, pz));
                }
                buffer.position(buffer.position() + 21);
            }
            return trajectory;

        } catch (Exception e) {
            System.err.println("CRASH en calculateRayPath: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        } finally {
            // 8. LIMPIEZA EXHAUSTIVA Y SEGURA
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, 0);
            glUseProgram(0);

            // Liberar los recursos locales que creamos en este método
            if (localDummyVAO != -1) glDeleteVertexArrays(localDummyVAO);
            if (dummyVBO != -1) glDeleteBuffers(dummyVBO);
        }
    }

    public static int uploadBlockTexture3D(Level level, BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        byte[] blockData = new byte[sizeX * sizeY * sizeZ];

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockPos worldPos = origin.offset(x, y, z);
                    boolean isSolid = !level.getBlockState(worldPos).isAir();
                    int index = (x * sizeY + y) * sizeZ + z;
                    blockData[index] = (byte) (isSolid ? 1 : 0);
                    if (isSolid) {
                        //System.out.println("[Wavecraft] 🔲 Textura3D: bloque sólido en " + worldPos);
                    }
                }
            }
        }

        int texID = GL33.glGenTextures();
        GL33.glBindTexture(GL33.GL_TEXTURE_3D, texID);
        //GL33.glTexImage3D(GL33.GL_TEXTURE_3D, 0, GL33.GL_R8, sizeX, sizeY, sizeZ, 0,
        //        GL33.GL_RED, GL33.GL_UNSIGNED_BYTE, ShaderHelper.createByteBuffer(blockData));

        byte[] rgbaData = new byte[sizeX * sizeY * sizeZ * 4];
        for (int i = 0; i < blockData.length; i++) {
            byte value = blockData[i]; // 1 si sólido, 0 si aire
            rgbaData[i*4 + 0] = (byte)(value == 1 ? 255 : 0); // R
            rgbaData[i * 4 + 1] = 0;     // G
            rgbaData[i * 4 + 2] = 0;     // B
            rgbaData[i * 4 + 3] = (byte) 255; // A
        }
        GL33.glTexImage3D(GL33.GL_TEXTURE_3D, 0, GL33.GL_RGBA8, sizeX, sizeY, sizeZ, 0,
                GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE, ShaderHelper.createByteBuffer(rgbaData));


        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_NEAREST);
        GL33.glTexParameteri(GL33.GL_TEXTURE_3D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);

        GL33.glBindTexture(GL33.GL_TEXTURE_3D, 0);

        return texID;
    }
    private static void checkGLError(String location) {
        int err;
        while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR) {
            System.err.printf("[GL_ERROR] %s: 0x%X%n", location, err);
        }
    }
    private static void clearGLErrors() {
        int e;
        while ((e = GL11.glGetError()) != GL11.GL_NO_ERROR) {
            // opcional: loggea e
        }
    }
}
