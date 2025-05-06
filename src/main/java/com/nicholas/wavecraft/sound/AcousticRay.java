package com.nicholas.wavecraft.sound;

import com.nicholas.wavecraft.debug.SoundDebugger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AcousticRay {
    private Vec3 currentPosition;
    private Vec3 currentDirection;
    private float intensity;
    public int bounces;
    private long tickLaunched;
    private long lastUpdatedTick;
    private float propagationSpeed; // m/s
    private float distanceAccumulator = 0;

    private final List<Integer> bounceIndices = new ArrayList<>();

    private final List<Vec3> path = new ArrayList<>();
    private static final float STEP_LENGTH = 1.0f;
    private static final int MAX_BOUNCES = 10;
    private static final float MAX_DISTANCE = 500.0f;
    private final ResourceLocation soundId;
    private final Vec3 sourcePos;

    private float distanceSinceLastBounce = 0;
    private static final float MAX_NO_REFLECTION_DISTANCE = 1000.0f;


    private float totalDistance = 0;

    public AcousticRay(Vec3 start, Vec3 direction, float intensity,
                       ResourceLocation soundId, Vec3 sourcePos) {
        this.currentPosition = start;
        this.currentDirection = direction.normalize();
        this.intensity = intensity;
        this.bounces = 0;
        this.path.add(start);
        this.distanceAccumulator = 0f;
        this.propagationSpeed = 343.0f;
        this.soundId   = soundId;
        this.sourcePos = sourcePos;
    }

    public void launchAtTick(long tick) {
        this.tickLaunched = tick;
        this.lastUpdatedTick = tick - 1; // ⚠️ Forzar que en el primer tick haya avance
    }

    public void setPropagationSpeed(float speed) {
        this.propagationSpeed = Math.max(speed, 0.01f); // 👈 Nunca menos de 0.01 m/s
    }


    public List<Vec3> getPathSegments() {
        return path;
    }

    public int getBounces() {
        return bounces;
    }

    public List<Integer> getBounceIndices() {
        return bounceIndices;
    }

    public float getDistanceTraveled(long currentTick) {
        float secondsElapsed = (currentTick - tickLaunched) / 20.0f;
        return propagationSpeed * secondsElapsed;
    }

    public boolean update(Level world, long currentTick) {
        float seconds = (currentTick - lastUpdatedTick) / 20.0f;
        if (seconds <= 0) return true;

        distanceAccumulator += propagationSpeed * seconds;
        lastUpdatedTick = currentTick;

        while (distanceAccumulator >= STEP_LENGTH && bounces < MAX_BOUNCES && totalDistance < MAX_DISTANCE && distanceSinceLastBounce < MAX_NO_REFLECTION_DISTANCE) {
            Vec3 nextPos = currentPosition.add(currentDirection.scale(STEP_LENGTH));

            // === ⬇️ DETECCIÓN Y REGISTRO DE IMPACTO CON PLANOS DEL JUGADOR ⬇️ ===
            boolean hitPlane = false;

            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) break;  // por seguridad en servidores

            Vec3 headPos = player.getEyePosition();
            Vec3 rightVec = player.getLookAngle().cross(new Vec3(0, 1, 0)).normalize();
            float half = SoundDebugger.dimensions / 2.0f;

            Map<RayImpulseCapture.Plane, Vec3> normals = Map.of(
                    RayImpulseCapture.Plane.XY, new Vec3(0, 0, 1),
                    RayImpulseCapture.Plane.XZ, new Vec3(0, 1, 0),
                    RayImpulseCapture.Plane.YZ, new Vec3(1, 0, 0)
            );

            for (var e : normals.entrySet()) {
                if (hitPlane) break;
                RayImpulseCapture.Plane plane = e.getKey();
                Vec3 normal = e.getValue();

                Vec3 hit = intersectSegmentWithPlane(currentPosition, nextPos, headPos, normal);
                if (hit == null) continue;
                Vec3 offset = hit.subtract(headPos);
                if (Math.max(Math.max(Math.abs(offset.x), Math.abs(offset.y)), Math.abs(offset.z)) > half) continue;


                double distSrc = sourcePos.distanceTo(hit);
                float tSec = (float) (distSrc / propagationSpeed);
                int bnc = this.bounces;
                float attenuation = 1.0f; // para el futuro

                double distHead = hit.distanceTo(headPos);

                boolean isRightEar;
                float weight;
                if (plane == RayImpulseCapture.Plane.XZ) {
                    isRightEar = (hit.x() > headPos.x());
                    weight = 1f;
                } else {
                    double dot = rightVec.dot(hit.subtract(headPos));
                    float norm = (float) ((dot / half) * 0.5 + 0.5);
                    weight = Math.min(1f, Math.max(0f, norm));
                    isRightEar = true; // se asignará a ambos luego
                }

                var cap = new RayImpulseCapture(
                        this.soundId,
                        this.sourcePos,
                        distSrc,
                        hit,
                        distHead,
                        tSec,
                        bnc,
                        attenuation,
                        plane,
                        weight,
                        plane == RayImpulseCapture.Plane.XZ ? isRightEar : true
                );

                if (plane == RayImpulseCapture.Plane.XZ) {
                    if (isRightEar) AcousticRayManager.impulseRight.add(cap);
                    else AcousticRayManager.impulseLeft.add(cap);
                } else {
                    var capL = new RayImpulseCapture(
                            cap.soundId(), cap.sourcePos(), cap.distanceFromSource(),
                            cap.hitPos(), cap.distanceToHead(), cap.timeSeconds(), cap.bounceCount(),
                            cap.totalAttenuation(), cap.plane(), 1 - cap.weight(), false
                    );
                    var capR = cap;
                    AcousticRayManager.impulseLeft.add(capL);
                    AcousticRayManager.impulseRight.add(capR);
                }

                String msg = String.format(
                        "[Wavecraft] %s ⏱%.3fs dist=%.2fm refl=%d plano=%s head=%.2fm",
                        cap.soundId().toString(),
                        cap.timeSeconds(),
                        cap.distanceFromSource(),
                        cap.bounceCount(),
                        cap.plane(),
                        cap.distanceToHead()
                );
                player.displayClientMessage(Component.literal(msg), false);
                hitPlane = true;
            }

            //if (hitPlane) return false;
            // === ⬆️ FIN BLOQUE DE COLISIÓN CON PLANOS DEL JUGADOR ⬆️ ===

            BlockHitResult hit = world.clip(new net.minecraft.world.level.ClipContext(
                    currentPosition,
                    nextPos,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    null
            ));

            if (hit.getType() != HitResult.Type.MISS && hit.getBlockPos().equals(BlockPos.containing(currentPosition))) {
                currentPosition = nextPos;
                path.add(nextPos);
                distanceAccumulator -= STEP_LENGTH;
                totalDistance += STEP_LENGTH;
                distanceSinceLastBounce += STEP_LENGTH;
                continue;
            }

            if (hit.getType() != HitResult.Type.MISS && isReflectiveBlock(world, hit.getBlockPos())) {
                currentPosition = hit.getLocation();
                path.add(currentPosition);
                bounceIndices.add(path.size() - 1);

                Vec3 normal = new Vec3(
                        hit.getDirection().getStepX(),
                        hit.getDirection().getStepY(),
                        hit.getDirection().getStepZ()
                );

                currentDirection = reflect(currentDirection, normal);
                bounces++;
                distanceSinceLastBounce = 0;
            } else {
                currentPosition = nextPos;
                path.add(nextPos);
                distanceSinceLastBounce += STEP_LENGTH;
            }

            totalDistance += STEP_LENGTH;
            distanceAccumulator -= STEP_LENGTH;
        }

        return bounces < MAX_BOUNCES && totalDistance < MAX_DISTANCE && distanceSinceLastBounce < MAX_NO_REFLECTION_DISTANCE;
    }


    private boolean isReflectiveBlock(Level world, BlockPos pos) {
        return world.getBlockState(pos).isCollisionShapeFullBlock(world, pos);
    }

    private Vec3 intersectSegmentWithPlane(Vec3 p0, Vec3 p1, Vec3 planePoint, Vec3 normal) {
        Vec3 dir = p1.subtract(p0);
        double denom = dir.dot(normal);
        if (Math.abs(denom) < 1e-6) return null; // paralelo

        double t = (planePoint.subtract(p0)).dot(normal) / denom;
        if (t >= 0 && t <= 1) {
            return p0.add(dir.scale(t));
        }
        return null;
    }

    private Vec3 reflect(Vec3 incoming, Vec3 normal) {
        return incoming.subtract(normal.scale(2 * incoming.dot(normal))).normalize();
    }
}
