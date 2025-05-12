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
    /*‑‑config‑‑*/
    private static final float STEP_LENGTH = 1.0f;   // m
    private static final int   MAX_BOUNCES = 10;
    private static final float MAX_DISTANCE = 400f;
    private static final float MAX_NO_REFLECTION_DISTANCE = 300f;

    /*‑‑estado‑‑*/
    private Vec3 currentPosition;
    private Vec3 currentDirection;
    private int  bounces;
    private long lastUpdatedTick;
    private float distanceAccumulator;
    private float totalDistance;
    private float distanceSinceLastBounce;
    private float propagationSpeed = 343f;

    /*‑‑flags‑‑*/
    private final boolean renderEnabled  = SoundDebugger.renderRays;
    private final boolean impulseEnabled = SoundDebugger.rayEmissionEnabled;

    /*‑‑para bloquear rebote0‑‑*/
    private final BlockPos sourceBlock;
    private boolean leftSourceBlock = false;

    /*‑‑datos fijos‑‑*/
    private final ResourceLocation soundId;
    private final Vec3 sourcePos;
    private final long tickLaunched;

    /*‑‑trayectoria para debug‑‑*/
    private final List<Vec3> path     = new ArrayList<>();
    private final List<Integer> bounceIndices = new ArrayList<>();

    public AcousticRay(Vec3 start, Vec3 dir, float intensity,
                       ResourceLocation soundId, Vec3 sourcePos, long currentTick) {

        this.currentPosition = start;
        this.currentDirection = dir.normalize();
        this.soundId   = soundId;
        this.sourcePos = sourcePos;

        this.sourceBlock = BlockPos.containing(start);
        this.tickLaunched = currentTick;
        this.lastUpdatedTick = currentTick - 1;

        if (renderEnabled) path.add(start);          // para el debug
    }

    /*─────────API usado por SoundDebugger────────*/
    public List<Vec3> getPathSegments() { return path; }
    public List<Integer> getBounceIndices() { return bounceIndices; }

    public float getDistanceTraveled(long currentTick) {
        float secondsElapsed = (currentTick - tickLaunched) / 20.0f;
        return propagationSpeed * secondsElapsed;
    }

    /*public void launchAtTick(long tick) {
        this.tickLaunched = tick;
        this.lastUpdatedTick = tick - 1;
    }*/

    public void setPropagationSpeed(float speed) {
        this.propagationSpeed = Math.max(speed, 0.01f);
    }

    public int getBounces() {
        return bounces;
    }

    /*─────────bucle de física + impulso────────*/
    public boolean update(Level world, long currentTick) {

        /* Propagamos TODO en un solo tick –velocidad infinita para la parte física */
        distanceAccumulator = MAX_DISTANCE;

        while (distanceAccumulator >= STEP_LENGTH &&
                bounces < MAX_BOUNCES &&
                totalDistance < MAX_DISTANCE &&
                distanceSinceLastBounce < MAX_NO_REFLECTION_DISTANCE) {

            Vec3 nextPos = currentPosition.add(currentDirection.scale(STEP_LENGTH));
            BlockPos nextBlock = BlockPos.containing(nextPos);

            /* Marcar que salimos del bloque emisor */
            if (!leftSourceBlock && !nextBlock.equals(sourceBlock))
                leftSourceBlock = true;

            /*Rayos impulso: capta si ya hubo ≥1 rebote */
            if (impulseEnabled && bounces > 0) collectImpulse(currentPosition, nextPos, currentTick);

            /* Test de colisión */
            BlockHitResult hit = world.clip(new net.minecraft.world.level.ClipContext(
                    currentPosition, nextPos,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, null));

            /*Ignorar caras del bloque emisor hasta salir */
            if (!leftSourceBlock && hit.getType() != HitResult.Type.MISS && hit.getBlockPos().equals(sourceBlock)) {
                advance(nextPos);
                continue;
            }

            /*Reflexión válida */
            if (leftSourceBlock && hit.getType() != HitResult.Type.MISS && isReflectiveBlock(world, hit.getBlockPos())) {
                Vec3 normal = new Vec3(hit.getDirection().getStepX(),
                        hit.getDirection().getStepY(),
                        hit.getDirection().getStepZ());

                /*descartar salida rasante */
                if (Math.abs(currentDirection.dot(normal)) > 0.99) {
                    advance(nextPos);
                    continue;
                }

                currentPosition = hit.getLocation();
                if (renderEnabled) {
                    path.add(currentPosition);
                    bounceIndices.add(path.size() - 1);
                }
                currentDirection = reflect(currentDirection, normal);
                bounces++;
                distanceSinceLastBounce = 0;

            } else {                               // sin reflexión
                advance(nextPos);
            }
        }
        return  (bounces              < MAX_BOUNCES) &&
                (totalDistance        < MAX_DISTANCE) &&
                (distanceAccumulator  >= STEP_LENGTH);
    }

    /*─────────helpers────────*/
    private void advance(Vec3 nextPos) {
        currentPosition = nextPos;
        if (renderEnabled) path.add(nextPos);
        distanceAccumulator      -= STEP_LENGTH;
        totalDistance            += STEP_LENGTH;
        distanceSinceLastBounce  += STEP_LENGTH;
    }

    private void collectImpulse(Vec3 from, Vec3 to, long tick) {
        LocalPlayer pl = Minecraft.getInstance().player;
        if (pl == null) return;

        Vec3 headPos = pl.getEyePosition();
        float half   = SoundDebugger.dimensions / 2f;

        Map<RayImpulseCapture.Plane, Vec3> normals = Map.of(
                RayImpulseCapture.Plane.XY, new Vec3(0,0,1),
                RayImpulseCapture.Plane.XZ, new Vec3(0,1,0),
                RayImpulseCapture.Plane.YZ, new Vec3(1,0,0));

        for (var e : normals.entrySet()) {
            Vec3 hit = intersectSegmentWithPlane(from, to, headPos, e.getValue());
            if (hit == null) continue;
            Vec3 offset = hit.subtract(headPos);
            if (Math.max(Math.max(Math.abs(offset.x), Math.abs(offset.y)), Math.abs(offset.z)) > half) continue;

            double distSrc = sourcePos.distanceTo(hit);
            float  tSec    = (float)(distSrc / 343f);
            double distHead= hit.distanceTo(headPos);

            Vec3 toSrc = sourcePos.subtract(headPos).normalize();
            Vec3 look  = pl.getLookAngle();
            Vec3 rEar  = look.cross(new Vec3(0,1,0)).normalize();
            Vec3 lEar  = rEar.scale(-1);

            float weightR = (float) (0.5f + 0.5f * Math.max(0, rEar.dot(toSrc)));
            float weightL = (float) (0.5f + 0.5f * Math.max(0, lEar.dot(toSrc)));

            ConvolutionManager.addCapture(
                    new RayImpulseCapture(soundId, sourcePos, distSrc, hit, distHead,
                            tSec, bounces, 1f, e.getKey(), weightR, true), tick);
            ConvolutionManager.addCapture(
                    new RayImpulseCapture(soundId, sourcePos, distSrc, hit, distHead,
                            tSec, bounces, 1f, e.getKey(), weightL, false), tick);
        }
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