package com.nicholas.wavecraft.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class AcousticRay {
    private Vec3 currentPosition;
    private Vec3 currentDirection;
    private float intensity;
    public int bounces;
    private long tickLaunched;
    private long lastUpdatedTick;
    private float propagationSpeed; // m/s
    private float distanceAccumulator = 0;

    private final List<Vec3> path = new ArrayList<>();
    private static final float STEP_LENGTH = 1.0f;
    private static final int MAX_BOUNCES = 10;
    private static final float MAX_DISTANCE = 500.0f;

    private float distanceSinceLastBounce = 0;
    private static final float MAX_NO_REFLECTION_DISTANCE = 1000.0f;


    private float totalDistance = 0;

    public AcousticRay(Vec3 start, Vec3 direction, float intensity) {
        this.currentPosition = start;
        this.currentDirection = direction.normalize();
        this.intensity = intensity;
        this.bounces = 0;
        this.path.add(start);
        this.distanceAccumulator = 0f;
        this.propagationSpeed = 343.0f;
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

    public float getDistanceTraveled(long currentTick) {
        float secondsElapsed = (currentTick - tickLaunched) / 20.0f;
        return propagationSpeed * secondsElapsed;
    }

    public boolean update(Level world, long currentTick) {
        float seconds = (currentTick - lastUpdatedTick) / 20.0f;
        //System.out.println("🧪 Tick=" + currentTick + " | seconds=" + seconds + " | speed=" + propagationSpeed);
        if (seconds <= 0) return true;

        distanceAccumulator += propagationSpeed * seconds;
        lastUpdatedTick = currentTick;

        while (distanceAccumulator >= STEP_LENGTH && bounces < MAX_BOUNCES && totalDistance < MAX_DISTANCE && distanceSinceLastBounce < MAX_NO_REFLECTION_DISTANCE) {
            Vec3 nextPos = currentPosition.add(currentDirection.scale(STEP_LENGTH));
            //System.out.println("  ➡️ Avanzando. Pos: " + currentPosition);


            BlockHitResult hit = world.clip(new net.minecraft.world.level.ClipContext(
                    currentPosition,
                    nextPos,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    null
            ));

            // Si impacta contra el mismo bloque de origen, ignoramos el rebote
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

    private Vec3 reflect(Vec3 incoming, Vec3 normal) {
        return incoming.subtract(normal.scale(2 * incoming.dot(normal))).normalize();
    }
}
