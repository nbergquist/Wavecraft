package com.nicholas.wavecraft.sound;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ShaderHelper {

    public static int currentNumPoints = 21;

    public static int getCurrentNumPoints() {
        return currentNumPoints;
    }

    public static int loadShader(int type, String filename) {
        int shaderID = GL20.glCreateShader(type);

        String source = loadShaderSource(filename);
        if (source == null || source.isEmpty()) {
            throw new RuntimeException("Shader source is empty for " + filename);
        }

        GL20.glShaderSource(shaderID, source);
        GL20.glCompileShader(shaderID);

        int compiled = GL20.glGetShaderi(shaderID, GL20.GL_COMPILE_STATUS);
        if (compiled == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shaderID);
            System.err.println("❌ Shader compilation failed for " + filename + ":\n" + log);
            throw new RuntimeException("Shader compilation failed for " + filename);
        } else {
            System.out.println("✅ Shader compiled successfully: " + filename + " (ID = " + shaderID + ")");
        }

        return shaderID;
    }

    private static String loadShaderSource(String filename) {
        try {
            InputStream in = ShaderHelper.class.getResourceAsStream("/assets/wavecraft/shaders/" + filename);
            if (in == null) {
                throw new RuntimeException("Shader file not found: " + filename);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder source = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                source.append(line).append("\n");
            }

            return source.toString();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error loading shader source for " + filename);
        }
    }
    public static ByteBuffer createByteBuffer(byte[] src) {
        ByteBuffer buffer = MemoryUtil.memAlloc(src.length);  // ← buffer directo, nativo
        buffer.put(src).flip();
        return buffer;
    }
}
