package com.nicholas.wavecraft.sound;

import com.nicholas.wavecraft.debug.SoundDebugger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
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
    private static final int MAX_RAYS = 10000;

    public static int getNumRays() {
        return numRays;
    }

    public static void setNumRays(int rays) {
        numRays = rays;
    }

    public static void setSoundSpeed(float newSpeed) {
        soundSpeed = newSpeed;
    }

    public static void tick(Level level, long currentTick) {
        Iterator<AcousticRay> it = activeRays.iterator();
        while (it.hasNext()) {
            AcousticRay ray = it.next();
            boolean alive = ray.update(level, currentTick);
            if (!alive) it.remove();
        }
    }





    public static void emitRays(Vec3 sourcePos, ResourceLocation soundId) {
        if (Minecraft.getInstance().level == null) {
            System.out.println("[WARN] No hay mundo cargado. Ignorando emisión de rayos.");
            return;
        }

        //long currentTick = Minecraft.getInstance().level.getGameTime();

        for (int i = 0; i < numRays; i++) {
            if (activeRays.size() < MAX_RAYS) {
                Vec3 dir = randomDirection();
                Vec3 safeStart = sourcePos.add(dir.normalize().scale(0.05)); // desplazamiento fuera del bloque
                AcousticRay ray = new AcousticRay(sourcePos, dir, 1f, soundId, sourcePos, SoundDebugger.currentTick);
                activeRays.add(ray);

                ray.setPropagationSpeed(soundSpeed);
                //ray.launchAtTick(SoundDebugger.currentTick);
                activeRays.add(ray);
            }
        }

        //System.out.println("[EMIT] Total rayos activos: " + activeRays.size());
    }


    public static List<AcousticRay> getActiveRays() {
        return new ArrayList<>(activeRays); // devuelve copia segura
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
