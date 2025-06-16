#version 330 core

layout(location = 0) in float dummy; // Input from dummyVAO in Java

uniform vec3 rayOrigin;        // Initial origin of the entire ray path
uniform vec3 rayDirection;     // Initial direction of the entire ray path
uniform int maxBounces;        // Max bounces for the entire ray path (used by gl_VertexID logic)
uniform sampler3D worldTexture; // 3D texture of the world blocks
uniform ivec3 worldOffset;     // Offset to translate world coords to texture volume coords

out vec3 outPosition; // Output: a single point of the ray's path (either origin or a hit point)
out float bounceStatus;
out vec3 outNormal;      // nuevo
out vec3 debugCoord_tex; // nuevo: coordenadas en textura
out float debugCode; // código de estado

out vec3 outRayOrigin;        // Origen del segmento en este rebote
out vec3 outRayDirection;     // Dirección del segmento (tras reflexión si aplica)
out vec3 outHitPointBeforeEpsilon; // Punto de impacto antes de aplicar offset
out vec3 outHitBlockCenter;   // Centro del bloque impactado
out float outAccumulatedT;    // Distancia recorrida hasta el impacto

const float epsilon = 0.01; // Small offset to push ray from surface after a bounce

// Function to check if a block is solid based on its coordinate within the texture volume
bool isSolidBlock(ivec3 blockCoord_tex) {
    // Para depurar, vamos a hacer que el DDA piense que CUALQUIER bloque
    // por debajo de y=10 es sólido.
    // Necesitamos usar las coordenadas del mundo, no las de la textura para esto.
    // Esta comprobación es temporal y asume que worldOffset está disponible.
    ivec3 blockCoord_world = blockCoord_tex + worldOffset;

    // Si estamos dentro de la caja de cristal (0 a 10), no lo consideramos sólido
    // para que el rayo pueda viajar por dentro. Consideramos las paredes (-1 y 11) sólidas.
    if (all(greaterThanEqual(blockCoord_world, ivec3(0))) && all(lessThan(blockCoord_world, ivec3(11)))) {
        return false; // Es el aire dentro de la caja
    }

    // Cualquier otra cosa (las paredes de la caja y el exterior) es sólida.
    return true;
}

// DDA function to find the next collision point
// segmentOrigin_world: Starting point of the current ray segment in world coordinates
// segmentDir_world: Direction of the current ray segment (normalized)
// segmentInitialBlock_tex: The texture-space coordinate of the block containing segmentOrigin_world
//                          (to avoid self-collision at the very start of a segment)
// hitNormal_world: Output parameter for the normal of the surface hit (in world coordinates)
vec3 ddaBounce(vec3 segmentOrigin_world, vec3 segmentDir_world, ivec3 segmentInitialBlock_tex, out vec3 hitNormal_world) {
    ivec3 currentBlock_world = ivec3(floor(segmentOrigin_world));  // Current block DDA is in (world coords)
    ivec3 currentVoxel = currentBlock_world - worldOffset;

    if (any(lessThan(currentVoxel, ivec3(0))) || any(greaterThanEqual(currentVoxel, textureSize(worldTexture, 0)))) {
        hitNormal_world = vec3(0.0);
        return segmentOrigin_world + segmentDir_world * 1000.0; // Muy lejos = sin colisión
    }

    // tDelta: distance in 't' to cross one unit in each axis direction
    vec3 tDelta = abs(vec3(1.0) / segmentDir_world);

    // step: direction to step in each axis (+1 or -1)
    ivec3 step = ivec3(sign(segmentDir_world));

    // sideDist: 't' from segmentOrigin_world to the nearest x,y,z grid line in the direction of 'step'
    vec3 sideDist;
    sideDist.x = (segmentDir_world.x > 0) ? (floor(segmentOrigin_world.x) + 1.0 - segmentOrigin_world.x) * tDelta.x : (segmentOrigin_world.x - floor(segmentOrigin_world.x)) * tDelta.x;
    sideDist.y = (segmentDir_world.y > 0) ? (floor(segmentOrigin_world.y) + 1.0 - segmentOrigin_world.y) * tDelta.y : (segmentOrigin_world.y - floor(segmentOrigin_world.y)) * tDelta.y;
    sideDist.z = (segmentDir_world.z > 0) ? (floor(segmentOrigin_world.z) + 1.0 - segmentOrigin_world.z) * tDelta.z : (segmentOrigin_world.z - floor(segmentOrigin_world.z)) * tDelta.z;

    float accumulated_t = 0.0; // Total 't' accumulated from segmentOrigin_world

    // Limit DDA steps to prevent infinite loops (e.g., if ray is perfectly axis-aligned and gets stuck)
    for (int i = 0; i < 128; i++) { // Max DDA steps for this single bounce search
        ivec3 currentBlock_tex = currentBlock_world - worldOffset; // Convert current world block to texture coord


        // Check for collision, ensuring it's not the block this DDA segment started in
        if (isSolidBlock(currentBlock_tex) && accumulated_t > epsilon) {
            // Collision detected with 'currentBlock_world'
            // 'accumulated_t' is the distance along the ray to the entry face of this solid block.
            vec3 hitPoint_world = segmentOrigin_world + segmentDir_world * accumulated_t;

            // Determine hit normal based on which axis was stepped into last
            // (This requires knowing which component of sideDist was chosen *before* this block was entered)
            // The normal points outwards from the hit surface.
            // A common way: if sideDist.x was chosen to enter, normal is (-step.x, 0, 0)
            // We need to determine which component of 'sideDist' was minimal *before* it was last updated.

            // Simplified normal determination based on 'hitPoint_world' relative to 'currentBlock_world'
            // This is a bit approximate but often works.
            // A more robust way is to store the axis of the last step.
            // For now, let's use the normal logic that was in your original shader, as it's tied to the DDA step axis.
            // This requires knowing which 'sideDist' component *would have been* chosen to exit the *previous* (air) block.
            // Or, which component was minimal that led us to `accumulated_t`.

            // Let's refine the normal from your original logic:
            // If sideDist.x (the t to hit next X-plane) < sideDist.y and sideDist.x < sideDist.z,
            // it means the *previous step* must have been along Y or Z, and we are now hitting an X-face of currentBlock_world.
            // The 'hitNormal_world' calculation you had was tied to the structure where sideDist was updated *then* checked.
            // If `current_block_world` is the one we just entered, and `step` is how we got here:
            // To enter `currentBlock_world.x` via `step.x`, the normal component is `-step.x`.

            // The most recent step determined entry.
            // If (sideDist.x == accumulated_t): we hit an X-face. normal is (-step.x,0,0)
            // We need to know which component of sideDist *equaled* accumulated_t.
            // This means storing which axis was chosen for the step that resulted in accumulated_t.
            // A simple way:
            float min_prev_sideDist = accumulated_t; // The t at which the hit occurred.

            // This logic for normal is tricky without storing the last stepped axis.
            // Your original normal calculation was:
            // if (sideDist.x < sideDist.y && sideDist.x < sideDist.z) hitNormal_world = vec3(-step.x, 0.0, 0.0);
            // else if (sideDist.y < sideDist.z) hitNormal_world = vec3(0.0, -step.y, 0.0);
            // else hitNormal_world = vec3(0.0, 0.0, -step.z);
            // This normal refers to the face of `currentBlock_world` that was entered. This should be correct.
            // The crucial part is that `sideDist` here should be the values *before* being updated for the *exit* of `currentBlock_world`.

            // Let's assume the check 'isSolidBlock' happens *before* advancing 'currentBlock_world' past the hit.
            // So, when 'currentBlock_world' is found solid, 'accumulated_t' is the distance to its entry face.

            // To determine normal: Compare (sideDist.x-tDelta.x), (sideDist.y-tDelta.y), (sideDist.z-tDelta.z)
            // These are the 't' values at which the ray entered the current 'currentBlock_world' from each axis.
            // Smallest of these indicates entry face. This implies `currentBlock_world` is already the solid one.
            // The `step` determines the direction of entry.
            // If ray entered from X-positive side (step.x = 1), normal is (-1, 0, 0). So normal.x = -step.x.

            // The normal should be based on which component of sideDist was smallest to *reach* `accumulated_t`.
            // Store the normal from the *previous* iteration's choice:
            vec3 lastSideDist = sideDist;

            // Avanzamos el DDA al siguiente límite de bloque
            if (sideDist.x < sideDist.y && sideDist.x < sideDist.z) {
                accumulated_t = sideDist.x;
                sideDist.x += tDelta.x;
                currentBlock_world.x += step.x;
                hitNormal_world = vec3(-step.x, 0.0, 0.0); // La normal es opuesta al paso en X
            } else if (sideDist.y < sideDist.z) {
                accumulated_t = sideDist.y;
                sideDist.y += tDelta.y;
                currentBlock_world.y += step.y;
                hitNormal_world = vec3(0.0, -step.y, 0.0); // La normal es opuesta al paso en Y
            } else {
                accumulated_t = sideDist.z;
                sideDist.z += tDelta.z;
                currentBlock_world.z += step.z;
                hitNormal_world = vec3(0.0, 0.0, -step.z); // La normal es opuesta al paso en Z
            }
            // This still feels a bit off. The original normal calculation based on which sideDist *would be chosen next*
            // (i.e., to exit the current block) might be more standard for DDA when the current block *is* the hit one.

            // Let's use the normal finding from your shader, assuming `sideDist` has been updated for `currentBlock_world`
            // No, if `currentBlock_world` is the one just entered and found solid, `sideDist`
            // still holds the t-values to *exit* the *previous* (air) block.
            // The normal is simpler: it's based on which axis caused the step into `currentBlock_world`.
            // This requires tracking the last stepped axis.

            // Simpler normal: based on comparing `hitPoint_world` to integer boundaries of `currentBlock_world`.
            // This is also complex.

            // Let's trust your original hit normal logic for a moment and assume it aligns with how sideDist is managed.
            // The critical part is returning `hitPoint_world`.
            // Temporarily, let's determine normal based on which t was minimal:
            // This requires comparing sideDist *before* they are incremented for the current block.

            // Fallback to a simpler normal for now if the precise one is hard:
            // Assume normal is just opposed to direction of entry for the axis that was hit.
            // This is hard to get right without restructuring DDA significantly or storing last step axis.

            // **A key insight from many DDAs: when `mapPos` is the solid block, the normal is determined
            // by which component of `sideDist` was smallest *before* `mapPos` was updated to be this solid block.**
            // This implies we need to store `hitNormal_world` from the *previous* DDA step choice.

            // For now, let's assume a simplified normal calculation for testing the hit point.
            // The most robust normal is found by determining which face of the AABB of currentBlock_world was hit.r
            // This means `hitPoint_world` will be on one of the 6 faces.
            // e.g. if abs(hitPoint_world.x - currentBlock_world.x) < epsilon, normal is (-1,0,0)
            //      if abs(hitPoint_world.x - (currentBlock_world.x+1)) < epsilon, normal is (1,0,0)

            // Let's try to derive normal based on which sideDist is "closest" to accumulated_t when a hit occurs
            // This logic determines which face was hit by comparing how much "room" was left in each sideDist
            // This version of normal finding is common:
            if (sideDist.x < sideDist.y && sideDist.x < sideDist.z) {
                hitNormal_world = vec3(-step.x, 0.0, 0.0);
            } else if (sideDist.y < sideDist.z) {
                hitNormal_world = vec3(0.0, -step.y, 0.0);
            } else {
                hitNormal_world = vec3(0.0, 0.0, -step.z);
            }
            // This implies that when `currentBlock_world` is solid, `sideDist` reflects how to exit it.
            // The normal of the face entered would be this.

            // Desplaza ligeramente el punto hacia fuera del bloque para evitar colisiones internas
            //hitPoint_world -= normalize(hitNormal_world) * epsilon;

            return hitPoint_world;
        }

        // Advance DDA to the next block boundary
        if (sideDist.x < sideDist.y && sideDist.x < sideDist.z) {
            accumulated_t = sideDist.x;
            sideDist.x += tDelta.x;
            currentBlock_world.x += step.x;
        } else if (sideDist.y < sideDist.z) {
            accumulated_t = sideDist.y;
            sideDist.y += tDelta.y;
            currentBlock_world.y += step.y;
        } else {
            accumulated_t = sideDist.z;
            sideDist.z += tDelta.z;
            currentBlock_world.z += step.z;
        }

        // Check if ray traveled too far without a hit within the DDA search range
        if (accumulated_t > 500.0) { // Max distance per DDA segment
            hitNormal_world = vec3(0.0); // No hit normal
            return segmentOrigin_world + segmentDir_world * 500.0; // Return far point
        }
    }

    // No collision found within DDA iteration limit
    hitNormal_world = vec3(0.0);
    // Return a point far along the original direction if no hit
    return segmentOrigin_world + segmentDir_world * 500.0; // Arbitrary large distance
}

void main() {
    // Initial setup for the entire ray path (across multiple bounces)
    vec3 currentRayOrigin_world = rayOrigin;
    vec3 currentRayDir_world = normalize(rayDirection);
    vec3 finalHitPoint_world = rayOrigin; // Default to origin if no bounces occur or for gl_VertexID = 0

    debugCode = -99.0;

    // gl_VertexID determines which bounce point we are calculating
    // gl_VertexID = 0 is the initial origin
    // gl_VertexID = 1 is the point after the first bounce
    // etc.

    if (gl_VertexID > 0) { // Only calculate bounces if not the first point
        for (int bounceNum = 0; bounceNum < gl_VertexID; bounceNum++) {
            vec3 hitNormal_world;
            // For DDA, determine the block containing the start of this specific segment
            ivec3 segmentStartBlock_tex = ivec3(floor(currentRayOrigin_world)) - worldOffset;

            //🧪 DEBUG
            outRayOrigin = currentRayOrigin_world;
            outRayDirection = currentRayDir_world;

            vec3 hitPoint_segment = ddaBounce(currentRayOrigin_world, currentRayDir_world, segmentStartBlock_tex, hitNormal_world);

            //🧪 DEBUG
            outHitPointBeforeEpsilon = hitPoint_segment;
            outAccumulatedT = length(hitPoint_segment - currentRayOrigin_world);

            if (length(hitNormal_world) < 0.1) { // No valid hit / normal means ray didn't hit anything
                finalHitPoint_world = hitPoint_segment;

                // Añadimos una pequeña codificación para identificar este fallo en Java
                finalHitPoint_world += vec3(0.12345); // <--- este valor se detecta luego en CPU
                bounceStatus = 0.0; // no hubo colisión
                debugCode = 0.0; // No hit
                break;
            }

            finalHitPoint_world = hitPoint_segment;

            if (ivec3(floor(finalHitPoint_world)) == ivec3(floor(currentRayOrigin_world))) {
                bounceStatus = -1.0; // rebote redundante en el mismo bloque
                debugCode = 2.0; // Rebotó en el mismo bloque
                break;
            }


            if (distance(finalHitPoint_world, currentRayOrigin_world) > 500.0) {
                debugCode = 4.0; // Viaje infinito
            }

            if (length(hitNormal_world) < 0.9) {
                debugCode = 3.0; // Normal rara aunque hubo hit
            }

            ivec3 hitBlock = ivec3(floor(finalHitPoint_world));
            if (isSolidBlock(hitBlock - worldOffset)) {
                vec3 distToSurface = abs(finalHitPoint_world - (vec3(hitBlock) + 0.5));
                if (all(lessThan(distToSurface, vec3(0.49)))) {
                    debugCode = 5.0; // Rebote dentro del bloque (no escapó)
                }
            }

            bounceStatus = 1.0; // rebote correcto

            // Prepare for next bounce
            hitNormal_world = normalize(hitNormal_world); // Normalización segura

            currentRayOrigin_world = finalHitPoint_world + hitNormal_world * epsilon;
            currentRayDir_world = reflect(currentRayDir_world, hitNormal_world); // Ya está normalizada

            outNormal = hitNormal_world; // Guardamos la versión correcta
            debugCoord_tex = vec3(floor(finalHitPoint_world) - worldOffset);
        }
    }
    if (gl_VertexID == 0) {
        bounceStatus = 1.0;
        debugCode = 10.0; // punto inicial
        outRayOrigin = rayOrigin;
        outRayDirection = normalize(rayDirection);
        outHitPointBeforeEpsilon = rayOrigin;
        outAccumulatedT = 0.0;
        outNormal = vec3(0.0);
        debugCoord_tex = vec3(0.0);
        outHitBlockCenter = vec3(0.0);
    }

    outPosition = finalHitPoint_world;
}