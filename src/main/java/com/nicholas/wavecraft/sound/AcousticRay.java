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
    private int bounces;
    private long tickLaunched;
    private long lastUpdatedTick;
    private float propagationSpeed;
    private float distanceAccumulator = 0;

    private final List<Integer> bounceIndices = new ArrayList<>();
    private final List<Vec3> path = new ArrayList<>();
    private static final float STEP_LENGTH = 1.0f;
    private static final int MAX_BOUNCES = 10;
    private static final float MAX_DISTANCE = 400.0f;
    private final ResourceLocation soundId;
    private final Vec3 sourcePos;

    private float distanceSinceLastBounce = 0;
    private static final float MAX_NO_REFLECTION_DISTANCE = 300.0f;
    private float totalDistance = 0;

    private boolean directImpulseAdded = false;

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
        this.lastUpdatedTick = tick - 1;
    }

    public void setPropagationSpeed(float speed) {
        this.propagationSpeed = Math.max(speed, 0.01f);
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

        while (distanceAccumulator >= STEP_LENGTH &&
                bounces < MAX_BOUNCES &&
                totalDistance < MAX_DISTANCE &&
                distanceSinceLastBounce < MAX_NO_REFLECTION_DISTANCE) {

            Vec3 nextPos = currentPosition.add(currentDirection.scale(STEP_LENGTH));

            if (bounces > 0) {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null) {
                    Vec3 headPos = player.getEyePosition();
                    Vec3 rightVec = player.getLookAngle().cross(new Vec3(0, 1, 0)).normalize();
                    float half = SoundDebugger.dimensions / 2.0f;

                    Map<RayImpulseCapture.Plane, Vec3> normals = Map.of(
                            RayImpulseCapture.Plane.XY, new Vec3(0, 0, 1),
                            RayImpulseCapture.Plane.XZ, new Vec3(0, 1, 0),
                            RayImpulseCapture.Plane.YZ, new Vec3(1, 0, 0)
                    );

                    for (var e : normals.entrySet()) {
                        RayImpulseCapture.Plane plane = e.getKey();
                        Vec3 normal = e.getValue();

                        Vec3 hit = intersectSegmentWithPlane(currentPosition, nextPos, headPos, normal);
                        if (hit == null) continue;
                        Vec3 offset = hit.subtract(headPos);
                        if (Math.max(Math.max(Math.abs(offset.x), Math.abs(offset.y)), Math.abs(offset.z)) > half) continue;

                        double distSrc = sourcePos.distanceTo(hit);
                        float tSec = (float) (distSrc / propagationSpeed);
                        float attenuation = 1.0f;
                        int bnc = this.bounces;
                        double distHead = hit.distanceTo(headPos);

                        Vec3 toSource = sourcePos.subtract(headPos).normalize();
                        Vec3 look     = player.getLookAngle();
                        Vec3 rightEar = look.cross(new Vec3(0,1,0)).normalize();
                        Vec3 leftEar  = rightEar.scale(-1);

                        float angAttR = getAngularAttenuation(toSource, rightEar);
                        float angAttL = getAngularAttenuation(toSource, leftEar);

                        float offsetFactor = 0.1f;
                        double cosR = Math.max(0f, rightEar.dot(toSource));
                        double cosL = Math.max(0f, leftEar .dot(toSource));

                        float weightR = (float) ((offsetFactor + (1 - offsetFactor) * cosR) * angAttR);
                        float weightL = (float) ((offsetFactor + (1 - offsetFactor) * cosL) * angAttL);

                        var capR = new RayImpulseCapture(
                                soundId, sourcePos, distSrc, hit, distHead,
                                tSec, bnc, attenuation,
                                plane, weightR, true
                        );
                        var capL = new RayImpulseCapture(
                                soundId, sourcePos, distSrc, hit, distHead,
                                tSec, bnc, attenuation,
                                plane, weightL, false
                        );

                        AcousticRayManager.impulseRight.add(capR);
                        AcousticRayManager.impulseLeft.add(capL);

                        if (SoundDebugger.renderCollisionPlanes) {
                            String msg = String.format(
                                    "[Wavecraft] %s \u23F1%.3fs dist=%.2fm refl=%d plano=%s head=%.2fm",
                                    soundId.toString(), tSec, distSrc, bnc, plane, distHead
                            );
                            player.displayClientMessage(Component.literal(msg), false);
                        }
                    }
                }
            }

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
        if (Math.abs(denom) < 1e-6) return null;

        double t = (planePoint.subtract(p0)).dot(normal) / denom;
        if (t >= 0 && t <= 1) {
            return p0.add(dir.scale(t));
        }
        return null;
    }

    private Vec3 reflect(Vec3 incoming, Vec3 normal) {
        return incoming.subtract(normal.scale(2 * incoming.dot(normal))).normalize();
    }

    private static float getAngularAttenuation(Vec3 toSource, Vec3 earDir) {
        return 1.0f;
    }
}
