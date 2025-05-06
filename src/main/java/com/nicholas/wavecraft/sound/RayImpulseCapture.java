// com/nicholas/wavecraft/sound/RayImpulseCapture.java
package com.nicholas.wavecraft.sound;

import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;

public record RayImpulseCapture(
        ResourceLocation soundId,
        Vec3 sourcePos,
        double distanceFromSource,
        Vec3 hitPos,
        double distanceToHead,
        float timeSeconds,
        int bounceCount,
        float totalAttenuation,         // preparado para futuros absorción
        Plane plane,                    // enum de qué plano colisionó
        float weight,                   // para binaural en planos “compartidos”
        boolean isRightEar
) {
    public enum Plane { XY, XZ, YZ }
}
