package com.nicholas.wavecraft.sound;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;

public class MaterialProperties {

    // Un mapa para guardar el coeficiente de absorción de cada bloque.
    private static final Map<Block, Float> ABSORPTION_COEFFICIENTS = new HashMap<>();

    // Un coeficiente por defecto para los bloques que no definamos explícitamente.
    private static final float DEFAULT_ABSORPTION = 0.2f; // 20% de absorción por defecto

    // Este bloque estático se ejecuta una sola vez cuando la clase se carga.
    // Aquí es donde definimos nuestras propiedades.
    static {
        // La lana negra absorbe el 100% del sonido.
        ABSORPTION_COEFFICIENTS.put(Blocks.BLACK_WOOL, 1.0f);

        // El cristal absorbe solo el 5% del sonido (refleja el 95%).
        ABSORPTION_COEFFICIENTS.put(Blocks.GLASS, 0.05f);
        ABSORPTION_COEFFICIENTS.put(Blocks.GLASS_PANE, 0.05f); // También para los paneles de cristal
        ABSORPTION_COEFFICIENTS.put(Blocks.TINTED_GLASS, 0.05f);

        // Puedes añadir más materiales aquí. Ejemplo:
        // ABSORPTION_COEFFICIENTS.put(Blocks.STONE, 0.1f); // La piedra absorbe el 10%
        // ABSORPTION_COEFFICIENTS.put(Blocks.BRICKS, 0.15f); // Los ladrillos absorben el 15%
    }

    /**
     * Devuelve el coeficiente de absorción para un bloque dado.
     * Si el bloque no está definido en nuestro mapa, devuelve el valor por defecto.
     */
    public static float getAbsorptionCoefficient(Block block) {
        return ABSORPTION_COEFFICIENTS.getOrDefault(block, DEFAULT_ABSORPTION);
    }
}