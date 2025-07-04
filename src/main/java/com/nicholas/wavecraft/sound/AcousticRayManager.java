package com.nicholas.wavecraft.sound;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nicholas.wavecraft.debug.SoundDebugger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

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

    // Este Set guardará las claves de los sonidos cuyo rayo directo ya hemos procesado.
    private final Set<String> directPathProcessed = new HashSet<>();

    private static float soundSpeed = 343.0f;

    public static float getSoundSpeed() {
        return soundSpeed;
    }

    private static int numRays = 100;
    private static final int MAX_RAYS = 10000;
    public static final float MAX_RAY_DISTANCE = 1000.0f;
    private static final int   MAX_RAY_BOUNCES  = 40;

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
                if (!ray.isExpired(currentTick)) {
                    // 1. Procesar la trayectoria del rayo mientras esté activo
                    checkRayPlaneIntersections(ray, player, level, currentTick);
                } else {
                    // 2. El rayo terminó su simulación; manejar visualización y limpieza
                    if (renderRays && !ray.isVisualExpired(currentTick)) {
                        SoundDebugger.addVisualRay(ray, currentTick);
                    }
                    if (ray.isVisualExpired(currentTick)) {
                        it.remove();
                    }
                }
            }
        }
    }


    public void spawnRay(Vec3 origin, Vec3 direction, float speed, long currentTick, ResourceLocation soundId) {
        // Añade un bloque sincronizado aquí para evitar la race condition
        synchronized (activeRays) {
            if (activeRays.size() < MAX_RAYS) {
                AcousticRay ray = new AcousticRay(origin, direction, speed, currentTick, soundId, MAX_RAY_BOUNCES);
                //activeRays.add(ray);
                synchronized (pendingRays) {
                    pendingRays.add(ray);
                }
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
            //manager.spawnRay(safeStartPos, dir, getSoundSpeed(), currentTick, soundId);
            this.spawnRay(safeStartPos, dir, getSoundSpeed(), currentTick, soundId);
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
    private void checkRayPlaneIntersections(AcousticRay ray, LocalPlayer player, Level level, long currentTick) {
        List<AcousticRay.PathPoint> path = ray.getInstantRay().getPathPoints();
        if (path.size() < 2) return;

        Vec3 playerPos = player.getEyePosition();
        Vec3 lookVec = player.getViewVector(1.0f);
        Vec3 rightVec = lookVec.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 upVec = player.getUpVector(1.0f);

        float cumulativeDistance = 0f;
        int bounceCount = 0;
        float reflectionAttenuation = 1.0f; // La energía del rayo, empieza al 100%

        for (int i = 0; i < path.size() - 1; i++) {
            AcousticRay.PathPoint startPoint = path.get(i);
            Vec3 start = startPoint.position();
            Vec3 end = path.get(i + 1).position();

            // 1. ACTUALIZAR la energía si hubo un rebote al inicio de este segmento.
            if (i > 0 && startPoint.bounceStatus() == 2.0f) {
                bounceCount++;

                BlockPos hitBlockPos = BlockPos.containing(startPoint.position().subtract(startPoint.normal().scale(0.01)));
                Block hitBlock = level.getBlockState(hitBlockPos).getBlock();

                // Usamos tu clase MaterialProperties para obtener la absorción
                float absorption = MaterialProperties.getAbsorptionCoefficient(hitBlock);
                reflectionAttenuation *= (1.0f - absorption);
            }

            // 2. COMPROBAR si el rayo sigue "vivo" CON LA ENERGÍA YA ACTUALIZADA.
            float distanceAttenuation = 1.0f / Math.max(1.0f, cumulativeDistance);
            float totalAttenuation = reflectionAttenuation * distanceAttenuation;

            // Si la atenuación total es demasiado alta, el rayo muere aquí y ahora.
            if (totalAttenuation < 0.001f) {
                // Le decimos al rayo que su simulación terminó en este punto.
                ray.setMaxAudibleDistance(cumulativeDistance);
                break; // Se detiene el bucle, no se procesa nada más para este rayo.
            }

            // 3. PROCESAR las intersecciones solo si el rayo sigue vivo.
            findIntersection(level, start, end, playerPos, rightVec, lookVec, upVec, rightVec, RayImpulseCapture.Plane.YZ, cumulativeDistance, ray, currentTick, i, bounceCount, reflectionAttenuation);
            findIntersection(level, start, end, playerPos, upVec, lookVec, rightVec, rightVec, RayImpulseCapture.Plane.XZ, cumulativeDistance, ray, currentTick, i, bounceCount, reflectionAttenuation);
            findIntersection(level, start, end, playerPos, lookVec, upVec, rightVec, rightVec, RayImpulseCapture.Plane.XY, cumulativeDistance, ray, currentTick, i, bounceCount, reflectionAttenuation);

            // 4. AVANZAR la distancia acumulada para la siguiente iteración.
            cumulativeDistance += (float) start.distanceTo(end);
        }
    }


    /**
     * Calcula si un segmento de línea intersecta un plano y, si lo hace, genera un RayImpulseCapture.
     */
    private void findIntersection(
            Level level,
            Vec3 start, Vec3 end,
            Vec3 planeOrigin, Vec3 planeNormal,
            Vec3 surfaceAxis1, Vec3 surfaceAxis2,
            Vec3 rightVec,
            RayImpulseCapture.Plane planeType,
            float distanceSoFar,
            AcousticRay ray,
            long currentTick,
            int segmentIndex,
            int bounceCountSoFar,
            float reflectionAttenuation // Parámetro de la atenuación por materiales
    ) {
        Vec3 segmentDir = end.subtract(start);
        double dotProduct = segmentDir.dot(planeNormal);

        if (Math.abs(dotProduct) < 1e-6) return;

        Vec3 startToPlane = planeOrigin.subtract(start);
        double t = startToPlane.dot(planeNormal) / dotProduct;

        // Comprueba si la intersección ocurre DENTRO del segmento de línea
        if (t >= 0 && t <= 1) {
            Vec3 intersectionPoint = start.add(segmentDir.scale(t));

            // Comprueba si la intersección ocurre DENTRO de los límites del plano
            Vec3 localHitPos = intersectionPoint.subtract(planeOrigin);
            float halfDim = SoundDebugger.dimensions / 2.0f;
            if (Math.abs(localHitPos.dot(surfaceAxis1)) > halfDim || Math.abs(localHitPos.dot(surfaceAxis2)) > halfDim) {
                return;
            }

            // --- INICIO DE LA LÓGICA DE CAPTURA AVANZADA ---

            // Regla #1: ¿Ya hemos capturado un impulso para este rayo en este MISMO nivel de rebote?
            if (bounceCountSoFar == ray.getLastCaptureBounceCount()) {
                return;
            }

            // Regla #2: Comprobación de Línea de Visión (Raycast)
            Vec3 lastBouncePoint = (bounceCountSoFar == 0) ? ray.getInstantRay().getPathPoints().get(0).position() : start;
            ClipContext context = new ClipContext(lastBouncePoint, planeOrigin, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, null);
            if (level.clip(context).getType() == HitResult.Type.BLOCK) {
                return; // ¡No hay impulso si el eco está bloqueado por un muro!
            }

            // --- CÁLCULOS FINALES (si hemos pasado todos los filtros) ---

            double virtualLegDistance = lastBouncePoint.distanceTo(planeOrigin);
            double totalVirtualDistance = distanceSoFar + virtualLegDistance;
            float timeSeconds = (float) (totalVirtualDistance / getSoundSpeed());

            // Atenuación por distancia (ley de la inversa)
            float distanceAttenuation = 1.0f / (float) Math.max(1.0, totalVirtualDistance);

            // Factor de mezcla para las reflexiones
            float reflectionMix = 1.0f;
            if (bounceCountSoFar > 0) {
                reflectionMix = SoundDebugger.reflectionsMixFactor;
            }

            // La atenuación final es el producto de todos los factores: distancia, material y mezcla.
            float finalAttenuation = distanceAttenuation * reflectionAttenuation * reflectionMix;

            // Cálculo de pesos binaurales
            float panFactor = (float)Math.max(-1.0, Math.min(1.0, localHitPos.dot(rightVec) / halfDim));
            float mix = SoundDebugger.binauralMixFactor;
            float finalWeightRight = (0.5f * (1.0f + panFactor)) * (1.0f - mix) + 0.5f * mix;
            float finalWeightLeft = (0.5f * (1.0f - panFactor)) * (1.0f - mix) + 0.5f * mix;

            // --- CREACIÓN Y ENVÍO DE IMPULSOS ---

            boolean impulseGenerated = false;
            Vec3 sourcePosition = ray.getInstantRay().getPathPoints().get(0).position();

            // Impulso para el oído izquierdo
            if (finalWeightLeft > 0.01f) {
                boolean isDirectPath = (bounceCountSoFar == 0);
                String directPathKeyLeft = ray.getSoundId().toString() + "_L";
                if (!isDirectPath || !directPathProcessed.contains(directPathKeyLeft)) {
                    if (isDirectPath) directPathProcessed.add(directPathKeyLeft);
                    RayImpulseCapture captureLeft = new RayImpulseCapture(ray.getSoundId(), sourcePosition, totalVirtualDistance, intersectionPoint, 0, timeSeconds, bounceCountSoFar, finalAttenuation, planeType, finalWeightLeft, false);
                    ConvolutionManager.addCapture(captureLeft, currentTick);
                    impulseGenerated = true;
                }
            }

            // Impulso para el oído derecho
            if (finalWeightRight > 0.01f) {
                boolean isDirectPath = (bounceCountSoFar == 0);
                String directPathKeyRight = ray.getSoundId().toString() + "_R";
                if (!isDirectPath || !directPathProcessed.contains(directPathKeyRight)) {
                    if (isDirectPath) directPathProcessed.add(directPathKeyRight);
                    RayImpulseCapture captureRight = new RayImpulseCapture(ray.getSoundId(), sourcePosition, totalVirtualDistance, intersectionPoint, 0, timeSeconds, bounceCountSoFar, finalAttenuation, planeType, finalWeightRight, true);
                    ConvolutionManager.addCapture(captureRight, currentTick);
                    impulseGenerated = true;
                }
            }

            // Si hemos generado al menos un impulso, actualizamos la memoria del rayo.
            if (impulseGenerated) {
                ray.setLastCaptureBounceCount(bounceCountSoFar);
            }
        }
    }

    /**
     * Se llama cuando los impulsos de un evento de sonido ya se han procesado,
     * para limpiar el registro de rayos directos y prepararse para el siguiente sonido.
     */
    public void onResponsesProcessed() {
        directPathProcessed.clear();
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
