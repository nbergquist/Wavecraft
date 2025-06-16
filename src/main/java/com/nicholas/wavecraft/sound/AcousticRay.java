package com.nicholas.wavecraft.sound;

import com.nicholas.wavecraft.commands.WavecraftCommand;
import com.nicholas.wavecraft.debug.SoundDebugger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.*;


public class AcousticRay {
    private final InstantRay instantRay;
    private final VisualRay visualRay;

    private final int maxBounces;

    private final ResourceLocation soundId;

    private final Set<Integer> processedSegments = new HashSet<>();

    //private long expireTick = Long.MAX_VALUE;
    private long simulationExpireTick;  // cuándo dejar de calcular
    private long visualExpireTick;      // cuándo dejar de renderizar

    public AcousticRay(Vec3 origin, Vec3 direction, float propagationSpeed, long currentTick, ResourceLocation soundId, int maxBounces) {
        this.instantRay = new InstantRay(origin, direction, Float.MAX_VALUE);
        this.visualRay = new VisualRay(instantRay, currentTick, AcousticRayManager.getSoundSpeed());

        this.soundId = soundId;
        this.maxBounces = maxBounces;

        if (SoundDebugger.renderRays) {
            float distance = Math.min(instantRay.getTotalLength(), AcousticRayManager.MAX_RAY_DISTANCE);
            long travelTimeTicks = (long) Math.ceil(distance / propagationSpeed);

            this.visualExpireTick = currentTick + travelTimeTicks;
        } else {
            this.visualExpireTick = currentTick + 1;
        }
        this.simulationExpireTick = this.visualExpireTick;
    }

    public InstantRay getInstantRay() { return instantRay; }
    public VisualRay getVisualRay() { return visualRay; }

    public ResourceLocation getSoundId() { return soundId; }

    public class InstantRay {
        private final List<Vec3> path = new ArrayList<>();
        private final List<Integer> bounceIndices = new ArrayList<>();
        private final float speed;

        public InstantRay(Vec3 origin, Vec3 direction, float speed) {
            this.speed = speed;
            calculatePath(origin, direction);
        }

        private void calculatePath(Vec3 origin, Vec3 direction) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            assert player != null;
            Level level = player.level();
            // 1. Pide a RayShaderHandler que calcule la trayectoria
            List<Vec3> trajectory = RayShaderHandler.calculateRayPath(level, player, origin, direction, speed, maxBounces);

            // 2. Guarda el resultado en patht/
            this.path.clear();
            this.path.addAll(trajectory);
        }


        public List<Vec3> getPath() { return path; }
        public List<Integer> getBounceIndices() { return bounceIndices; }

        public float getTotalLength() {
            float total = 0;
            for (int i = 1; i < path.size(); i++)
                total += (float) path.get(i).distanceTo(path.get(i-1));
            return total;
        }

    }

    public class VisualRay {
        private final InstantRay instantRay;
        private final long tickLaunched;
        private final float propagationSpeed;

        public VisualRay(InstantRay instantRay, long tickLaunched, float propagationSpeed) {
            this.instantRay = instantRay;
            this.tickLaunched = tickLaunched;
            this.propagationSpeed = propagationSpeed;
        }

        public float getDistanceTraveled(long currentTick) {
            return ((currentTick - tickLaunched) / 20f) * propagationSpeed;
        }
        public float getTickLaunched() { return tickLaunched; }

        public List<Vec3> getPath() { return instantRay.getPath();}
        public List<Integer> getBounceIndices() { return instantRay.getBounceIndices(); }

        public List<Vec3> buildRenderPath(long currentTick) {
            // --- INICIO DE SECCIÓN DE DEBUG ---
            if (currentTick % 5 == 0) { // Imprimir solo cada 5 ticks para no saturar la consola
                float debugMaxDistance = getDistanceTraveled(currentTick);
                /*System.out.println(
                        "[DEBUG RENDER] Tick: " + currentTick +
                                " | Rayo lanzado en: " + this.tickLaunched +
                                " | Ticks transcurridos: " + (currentTick - this.tickLaunched) +
                                " | Velocidad: " + this.propagationSpeed +
                                " | MaxDist Calculada: " + String.format("%.2f", debugMaxDistance) +
                                " | Puntos en Origen (Shader): " + this.instantRay.getPath().size()
                );*/
            }
            // --- FIN DE SECCIÓN DE DEBUG ---


            // Lógica de propagación (la versión corregida que te pasé antes)
            float maxDistance = getDistanceTraveled(currentTick);
            List<Vec3> src = instantRay.getPath();
            List<Vec3> dst = new ArrayList<>();

            if (src.isEmpty()) {
                return dst;
            }

            dst.add(src.get(0));
            float accumulatedDistance = 0f;

            for (int i = 0; i < src.size() - 1; i++) {
                Vec3 start = src.get(i);
                Vec3 end = src.get(i + 1);
                float segmentLength = (float) start.distanceTo(end);

                if (accumulatedDistance + segmentLength > maxDistance) {
                    if (segmentLength == 0) continue;
                    float remainingDistance = maxDistance - accumulatedDistance;
                    float t = remainingDistance / segmentLength;
                    Vec3 finalPoint = start.add(end.subtract(start).scale(t));
                    dst.add(finalPoint);
                    return dst;
                } else {
                    dst.add(end);
                    accumulatedDistance += segmentLength;
                }
            }
            return dst;
        }
    }

    public boolean isExpired(long currentTick) {
        return currentTick >= simulationExpireTick;
    }

    public boolean isVisualExpired(long currentTick) {
        return currentTick >= visualExpireTick;
    }
    public boolean hasSegmentBeenProcessed(int segmentIndex) {
        return processedSegments.contains(segmentIndex);
    }

    public void markSegmentAsProcessed(int segmentIndex) {
        processedSegments.add(segmentIndex);
    }

}