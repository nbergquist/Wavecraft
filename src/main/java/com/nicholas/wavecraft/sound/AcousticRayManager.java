package com.nicholas.wavecraft.sound;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nicholas.wavecraft.debug.SoundDebugger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static com.nicholas.wavecraft.debug.SoundDebugger.renderRays;


public class AcousticRayManager {
    // --- Inicio: Implementación del Singleton ---

    // 1. La única instancia de la clase, privada y final.
    private static final AcousticRayManager INSTANCE = new AcousticRayManager();

    // 2. Constructor privado para evitar que se creen otras instancias.
    private AcousticRayManager() {}

    // 3. Método público para obtener la única instancia.
    public static AcousticRayManager getInstance() {
        return INSTANCE;
    }

    // --- Fin: Implementación del Singleton ---
    private final List<AcousticRay> activeRays = new ArrayList<>();
    private final List<AcousticRay> pendingRays = new ArrayList<>(); // La lista de espera para los rayos recién creados.

    //public final List<RayImpulseCapture> impulseLeft = new ArrayList<>();
    //public final List<RayImpulseCapture> impulseRight = new ArrayList<>();


    private static float soundSpeed = 343.0f;

    public static float getSoundSpeed() {
        return soundSpeed;
    }

    private static int numRays = 1;
    private static final int MAX_RAYS = 10000;
    public static final float MAX_RAY_DISTANCE = 500.0f;
    private static final int   MAX_RAY_BOUNCES  = 20;

    public static int getNumRays() {
        return numRays;
    }

    private int rayCheckIndex = 0; // Índice para saber por dónde empezar a comprobar

    public List<AcousticRay> getActiveRays() {
        return new ArrayList<>(activeRays);
    }

    public void setNumRays(int rays) {
        numRays = rays;
    }

    public void setSoundSpeed(float newSpeed) {
        soundSpeed = newSpeed;
    }

    public List<AcousticRay.VisualRay> getVisualRaysToRender(long currentTick) {
        List<AcousticRay.VisualRay> raysToRender = new ArrayList<>();
        synchronized (activeRays) {
            // Aquí podrías añadir lógica para filtrar rayos expirados o actualizar su estado visual
            // Por ejemplo, si VisualRay tiene un método isAlive(currentTick)
            // activeRays.removeIf(ray -> !ray.getVisualRay().isAlive(currentTick));

            for (AcousticRay ray : activeRays) {
                raysToRender.add(ray.getVisualRay());
            }
        }
        return raysToRender;
    }

    public void tick(Level level, long currentTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        // Usamos un bloque sincronizado para toda la operación, garantizando seguridad
        synchronized (activeRays) {

            // 1. Añadir rayos pendientes: Movemos los nuevos rayos de la lista de espera a la principal.
            // Esto se hace antes de empezar a iterar para asegurar que se procesen en el mismo tick.
            if (!pendingRays.isEmpty()) {
                activeRays.addAll(pendingRays);
                pendingRays.clear();
            }

            // 2. Iterar y procesar con un ÚNICO bucle seguro.
            // Usamos un Iterador explícito, que es la única forma segura de eliminar
            // elementos de una lista mientras se recorre.
            Iterator<AcousticRay> it = activeRays.iterator();
            while (it.hasNext()) {
                AcousticRay ray = it.next();
                if (ray.isExpired(currentTick)) {
                    if (renderRays && !ray.isVisualExpired(currentTick)) {
                        SoundDebugger.addVisualRay(ray, currentTick);
                    }
                    if (!ray.isExpired(currentTick)) {
                        checkRayPlaneIntersections(ray, player, currentTick);
                    }
                    if (ray.isVisualExpired(currentTick)) {
                        it.remove(); // solo borrar cuando ya no se necesita ni para renderizar
                    }
                }
            }
        }
    }


    public void spawnRay(Vec3 origin, Vec3 direction, float speed, long currentTick, ResourceLocation soundId) {
        // Añade un bloque sincronizado aquí para evitar la race condition
        synchronized (activeRays) {
            AcousticRay ray = new AcousticRay(origin, direction, speed, currentTick, soundId, MAX_RAY_BOUNCES);
            //activeRays.add(ray);
            synchronized (pendingRays) {
                pendingRays.add(ray);
            }
        }
    }

    public void emitRays(Vec3 sourcePos, ResourceLocation soundId, long currentTick) {
        final AcousticRayManager manager = this;
        Random random = new Random();

        for (int i = 0; i < numRays; i++) {
            Vec3 dir = randomDirection(random);
            Vec3 safeStartPos = sourcePos.add(dir.scale(0.1));

            // --- LA CORRECCIÓN FINAL ---
            // RenderSystem.recordRenderCall() asegura que nuestro código OpenGL
            // se ejecuta en el momento adecuado dentro del ciclo de renderizado de Minecraft,
            // evitando conflictos con otros procesos de renderizado.
            //RenderSystem.recordRenderCall(() -> {
            //    manager.spawnRay(safeStartPos, dir, getSoundSpeed(), currentTick, soundId);
            //});
            manager.spawnRay(safeStartPos, dir, getSoundSpeed(), currentTick, soundId);
        }
    }

    private Vec3 randomDirection(Random random) {
        double theta = random.nextDouble() * 2 * Math.PI;
        double phi = Math.acos(2 * random.nextDouble() - 1);
        double x = Math.sin(phi) * Math.cos(theta);
        double y = Math.sin(phi) * Math.sin(theta);
        double z = Math.cos(phi);
        return new Vec3(x, y, z);
    }

    /**
     * Comprueba cada segmento de un rayo contra los tres planos ortogonales del jugador.
     * Si encuentra una intersección válida que no ha sido procesada, genera un impulso.
     */
    private void checkRayPlaneIntersections(AcousticRay ray, LocalPlayer player, long currentTick) {
        List<Vec3> path = ray.getInstantRay().getPath();
        if (path.size() < 2) return;

        Vec3 playerPos = player.getEyePosition(); // El centro de los planos de colisión

        // --- LÓGICA CORREGIDA: OBTENER NORMALES DINÁMICAS DEL JUGADOR ---
        // Estos vectores cambian cada tick según hacia dónde mire el jugador.
        Vec3 lookVec = player.getViewVector(1.0f);     // Normal del plano XY local del jugador
        Vec3 rightVec = lookVec.cross(new Vec3(0, 1, 0)).normalize(); // Normal del plano YZ local del jugador
        Vec3 upVec = player.getUpVector(1.0f);         // Normal del plano XZ local del jugador

        float cumulativeDistance = 0f;

        for (int i = 0; i < path.size() - 1; i++) {
            if (ray.hasSegmentBeenProcessed(i)) {
                cumulativeDistance += (float) path.get(i).distanceTo(path.get(i+1));
                continue;
            }

            Vec3 start = path.get(i);
            Vec3 end = path.get(i + 1);

            // Comprobar contra cada uno de los 3 planos orientados con el jugador
            findIntersection(start, end, playerPos, rightVec, RayImpulseCapture.Plane.YZ, cumulativeDistance, ray, currentTick, i);
            findIntersection(start, end, playerPos, upVec, RayImpulseCapture.Plane.XZ, cumulativeDistance, ray, currentTick, i);
            findIntersection(start, end, playerPos, lookVec, RayImpulseCapture.Plane.XY, cumulativeDistance, ray, currentTick, i);

            cumulativeDistance += (float) start.distanceTo(end);
        }
    }

    /**
     * Calcula si un segmento de línea intersecta un plano y, si lo hace, genera un RayImpulseCapture.
     */
    private void findIntersection(Vec3 start, Vec3 end, Vec3 planeOrigin, Vec3 planeNormal, RayImpulseCapture.Plane planeType, float distanceSoFar, AcousticRay ray, long currentTick, int segmentIndex) {
        Vec3 segmentDir = end.subtract(start);
        double dotProduct = segmentDir.dot(planeNormal);

        if (Math.abs(dotProduct) < 1e-6) {
            return;
        }

        Vec3 startToPlane = planeOrigin.subtract(start);
        double t = startToPlane.dot(planeNormal) / dotProduct;

        if (t >= 0 && t <= 1) {
            Vec3 intersectionPoint = start.add(segmentDir.scale(t));

            // Marcar el segmento como procesado para este tipo de plano para evitar duplicados.
            // (Una implementación más robusta podría usar un objeto más complejo que un simple Set<Integer>)
            ray.markSegmentAsProcessed(segmentIndex);

            // ... El resto de la lógica para crear y añadir el RayImpulseCapture es la misma ...
            double distInSegment = start.distanceTo(intersectionPoint);
            double totalDistance = distanceSoFar + distInSegment;
            float timeSeconds = (float) (totalDistance / getSoundSpeed());

            boolean isRightEar = (planeType == RayImpulseCapture.Plane.YZ && dotProduct > 0);

            RayImpulseCapture capture = new RayImpulseCapture(
                    ray.getSoundId(),
                    ray.getInstantRay().getPath().get(0),
                    totalDistance,
                    intersectionPoint,
                    0,
                    timeSeconds,
                    ray.getInstantRay().getBounceIndices().size(),
                    1.0f,
                    planeType,
                    1.0f,
                    isRightEar
            );

            ConvolutionManager.addCapture(capture, currentTick);
        }
    }

    /**
     * Determina a qué canal de audio (oído) pertenece un impulso según el plano que cruza.
     * Esta es una simplificación; podría mejorarse con la orientación del jugador.
     */
    private boolean determineEarForPlane(RayImpulseCapture.Plane plane, double dotProduct) {
        // Esta es una lógica simple. Asume que el jugador mira hacia -Z.
        // Un producto punto negativo significa que el rayo viaja en dirección opuesta al normal.
        switch (plane) {
            case YZ: // Normal en X
                return dotProduct < 0; // Rayo viene de -X (izquierda) hacia +X (derecha)
            case XZ: // Normal en Y
                return false; // Arriba/Abajo, lo asignamos a ambos o a ninguno por ahora
            case XY: // Normal en Z
                return false; // Delante/Detrás, también neutro por ahora
        }
        return false;
    }

}
