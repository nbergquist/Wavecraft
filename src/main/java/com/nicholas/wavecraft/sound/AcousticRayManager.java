package com.nicholas.wavecraft.sound;

import com.nicholas.wavecraft.debug.SoundDebugger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;


public class AcousticRayManager {
    private static final List<AcousticRay> activeRays = new ArrayList<>();
    public static final List<RayImpulseCapture> impulseLeft = new ArrayList<>();
    public static final List<RayImpulseCapture> impulseRight = new ArrayList<>();


    private static float soundSpeed = 343.0f;

    public static float getSoundSpeed() {
        return soundSpeed;
    }

    private static int numRays = 200;

    public static int getNumRays() {
        return numRays;
    }

    public static List<AcousticRay> getActiveRays() {
        return new ArrayList<>(activeRays);
    }

    public static void setNumRays(int rays) {
        numRays = rays;
    }

    public static void setSoundSpeed(float newSpeed) {
        soundSpeed = newSpeed;
    }

    public static void tick(Level level, long currentTick) {
        // Copiar la lista para evitar errores si se modifica mientras iteramos
        List<AcousticRay> raysCopy = new ArrayList<>(activeRays);
        List<AcousticRay> toRemove = new ArrayList<>();

        for (AcousticRay ray : raysCopy) {
            boolean alive = ray.update(level, currentTick);
            if (!alive) {
                toRemove.add(ray);
            }
        }

        // Eliminar todos los rayos que han terminado
        activeRays.removeAll(toRemove);
    }




    public static void emitRays(Vec3 sourcePos, ResourceLocation soundId) {
        if (Minecraft.getInstance().level == null) {
            System.out.println("[WARN] No hay mundo cargado. Ignorando emisión de rayos.");
            return;
        }

        //long currentTick = Minecraft.getInstance().level.getGameTime();

        for (int i = 0; i < numRays; i++) {
            Vec3 dir = randomDirection();
            Vec3 safeStart = sourcePos.add(dir.normalize().scale(0.05)); // desplazamiento fuera del bloque
            AcousticRay ray = new AcousticRay(safeStart, dir, 1.0f, soundId, sourcePos);

            ray.setPropagationSpeed(soundSpeed);
            ray.launchAtTick(SoundDebugger.currentTick);
            activeRays.add(ray);
        }

        //System.out.println("[EMIT] Total rayos activos: " + activeRays.size());
    }

    private static Vec3 randomDirection() {
        // Puedes mejorar esto para direcciones uniformes esféricamente
        double theta = Math.random() * 2 * Math.PI;
        double phi = Math.acos(2 * Math.random() - 1);
        return new Vec3(
                Math.sin(phi) * Math.cos(theta),
                Math.sin(phi) * Math.sin(theta),
                Math.cos(phi)
        );
    }
}
