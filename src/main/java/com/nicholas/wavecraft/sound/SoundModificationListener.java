package com.nicholas.wavecraft.sound;

import com.nicholas.wavecraft.debug.SoundDebugger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

public class SoundModificationListener extends SimplePreparableReloadListener<Void> {

    @Override
    protected Void prepare(ResourceManager pResourceManager, ProfilerFiller pProfiler) {
        return null;
    }

    @Override
    protected void apply(Void pObject, ResourceManager pResourceManager, ProfilerFiller pProfiler) {
        System.out.println("[Wavecraft] Aplicando multiplicador de atenuación a los sonidos...");

        try {
            SoundManager soundManager = Minecraft.getInstance().getSoundManager();

            // --- INICIO DE LA LÓGICA DE REFLEXIÓN ROBUSTA ---

            // 1. Encontrar el campo 'soundRegistry' buscándolo por su tipo (Map), no por su nombre.
            Field soundRegistryField = findFieldByType(SoundManager.class, Map.class);
            if (soundRegistryField == null) throw new NoSuchFieldException("No se pudo encontrar el campo de tipo Map en SoundManager.");
            soundRegistryField.setAccessible(true);
            Map<ResourceLocation, WeighedSoundEvents> soundRegistry = (Map<ResourceLocation, WeighedSoundEvents>) soundRegistryField.get(soundManager);

            // 2. Encontrar el campo 'sounds' en WeighedSoundEvents buscándolo por su tipo (List).
            Field soundsListField = findFieldByType(WeighedSoundEvents.class, List.class);
            if (soundsListField == null) throw new NoSuchFieldException("No se pudo encontrar el campo de tipo List en WeighedSoundEvents.");
            soundsListField.setAccessible(true);

            // 3. Encontrar el campo 'attenuationDistance' en Sound. Este nombre es más estable.
            Field attenuationDistanceField = Sound.class.getDeclaredField("attenuationDistance");
            attenuationDistanceField.setAccessible(true);

            // Esto sigue siendo necesario para modificar un campo 'final'.
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);

            // --- FIN DE LA LÓGICA DE REFLEXIÓN ROBUSTA ---

            int modifiedCount = 0;
            for (WeighedSoundEvents weighedSoundEvents : soundRegistry.values()) {
                List<Sound> sounds = (List<Sound>) soundsListField.get(weighedSoundEvents);
                for (Sound sound : sounds) {
                    modifiersField.setInt(attenuationDistanceField, attenuationDistanceField.getModifiers() & ~Modifier.FINAL);

                    int originalDistance = sound.getAttenuationDistance();
                    int newDistance = (int) (originalDistance * SoundDebugger.globalAttenuationMultiplier);
                    attenuationDistanceField.setInt(sound, newDistance);
                    modifiedCount++;
                }
            }
            System.out.println("[Wavecraft] Modificadas las distancias de atenuación de " + modifiedCount + " sonidos.");

        } catch (Exception e) {
            System.err.println("[Wavecraft] ERROR: Fallo al modificar la distancia de los sonidos usando reflexión.");
            e.printStackTrace();
        }
    }

    /**
     * Un método de ayuda para encontrar un campo privado en una clase basándose en su tipo,
     * para evitar depender de nombres ofuscados.
     */
    private static Field findFieldByType(Class<?> ownerClass, Class<?> fieldType) {
        for (Field field : ownerClass.getDeclaredFields()) {
            if (field.getType().equals(fieldType)) {
                return field;
            }
        }
        return null;
    }
}