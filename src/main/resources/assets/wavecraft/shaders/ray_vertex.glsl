#version 330 core

layout(location = 0) in float dummy;

// --- UNIFORMS ---
uniform vec3 rayOrigin;
uniform vec3 rayDirection;
uniform sampler3D worldTexture;
uniform ivec3 worldOffset;

// --- SALIDAS PARA TRANSFORM FEEDBACK ---
out vec3 outPosition;
out float bounceStatus;
out vec3 outNormal;
out vec3 debugCoord_tex;
out float debugCode;
out vec3 outRayOrigin;
out vec3 outRayDirection;
out vec3 outHitPointBeforeEpsilon;
out vec3 outHitBlockCenter;
out float outAccumulatedT;

// --- CONSTANTES ---
const float epsilon = 0.001;
const int maxDdaSteps = 256;
const float maxDistance = 500.0;

bool isSolidBlock(ivec3 blockCoord_tex) {
    ivec3 texSize = textureSize(worldTexture, 0);
    if (any(lessThan(blockCoord_tex, ivec3(0))) || any(greaterThanEqual(blockCoord_tex, texSize))) {
        return false;
    }
    return texelFetch(worldTexture, blockCoord_tex, 0).r > 0.5;
}

// La función DDA no cambia. La lógica principal se mueve a main.
vec3 ddaBounce(vec3 segmentOrigin_world, vec3 segmentDir_world, out vec3 hitNormal_world, out float travelDistance) {
    ivec3 mapPos = ivec3(floor(segmentOrigin_world));
    vec3 tDelta = abs(vec3(1.0) / segmentDir_world);
    ivec3 step = ivec3(sign(segmentDir_world));
    vec3 sideDist;

    sideDist.x = (step.x > 0) ? (mapPos.x + 1.0 - segmentOrigin_world.x) * tDelta.x : (segmentOrigin_world.x - mapPos.x) * tDelta.x;
    sideDist.y = (step.y > 0) ? (mapPos.y + 1.0 - segmentOrigin_world.y) * tDelta.y : (segmentOrigin_world.y - mapPos.y) * tDelta.y;
    sideDist.z = (step.z > 0) ? (mapPos.z + 1.0 - segmentOrigin_world.z) * tDelta.z : (segmentOrigin_world.z - mapPos.z) * tDelta.z;

    travelDistance = 0.0;
    hitNormal_world = vec3(0.0);

    for (int i = 0; i < maxDdaSteps; i++) {
        if (sideDist.x < sideDist.y && sideDist.x < sideDist.z) {
            travelDistance = sideDist.x;
            sideDist.x += tDelta.x;
            mapPos.x += step.x;
            hitNormal_world = vec3(-step.x, 0.0, 0.0);
        } else if (sideDist.y < sideDist.z) {
            travelDistance = sideDist.y;
            sideDist.y += tDelta.y;
            mapPos.y += step.y;
            hitNormal_world = vec3(0.0, -step.y, 0.0);
        } else {
            travelDistance = sideDist.z;
            sideDist.z += tDelta.z;
            mapPos.z += step.z;
            hitNormal_world = vec3(0.0, 0.0, -step.z);
        }

        if (travelDistance > maxDistance) {
            hitNormal_world = vec3(0.0);
            return segmentOrigin_world + segmentDir_world * maxDistance;
        }

        debugCode = float((mapPos.x - worldOffset.x) + (mapPos.y - worldOffset.y)*100 + (mapPos.z - worldOffset.z)*10000);

        if (isSolidBlock(mapPos - worldOffset)) {
            return segmentOrigin_world + segmentDir_world * travelDistance;
        }
    }

    hitNormal_world = vec3(0.0);
    return segmentOrigin_world + segmentDir_world * maxDistance;
}


void main() {
    // --- Configuración Inicial ---
    vec3 currentRayOrigin = rayOrigin;
    vec3 currentRayDir = normalize(rayDirection);

    // --- INICIALIZACIÓN DE SALIDAS ---
    outPosition = rayOrigin;
    bounceStatus = 1.0;
    outNormal = vec3(0.0);
    debugCode = 10.0;
    // (El resto de variables de debug se asignarán dentro del bucle)

    // forzar uso de dummy y evitar que el compilador lo elimine
    float _keepDummy = dummy;
    if (_keepDummy < 0.0) {
        // nunca ocurre, solo para retener dummy
        gl_Position = vec4(0.0);
    }

    if (gl_VertexID == 0) {
        // Emitir el punto inicial del rayo sin rebotes
        outPosition = rayOrigin;
        bounceStatus = 1.0;
        outNormal = vec3(0.0);
        debugCode = 0.0;
        debugCoord_tex = vec3(0.0);
        outRayOrigin = rayOrigin;
        outRayDirection = normalize(rayDirection);
        outHitPointBeforeEpsilon = rayOrigin;
        outHitBlockCenter = floor(rayOrigin) + 0.5;
        outAccumulatedT = 0.0;
        return;
    }

    // --- ¡NUEVA LÓGICA DE INICIO SEGURO! ---
    // Si el rayo nace DENTRO de un bloque sólido, lo avanzamos un paso
    // para que empiece en el aire y no se auto-interseque.
    ivec3 startBlock = ivec3(floor(currentRayOrigin));
    if (isSolidBlock(startBlock - worldOffset)) {
        // Da un pequeño paso para salir del bloque inicial
        currentRayOrigin += currentRayDir * epsilon * 10.0;
    }
    // ------------------------------------

    for (int bounceNum = 0; bounceNum < gl_VertexID; bounceNum++) {
        vec3 hitNormal;
        float travelDistance;

        outRayOrigin = currentRayOrigin;
        outRayDirection = currentRayDir;

        vec3 hitPoint = ddaBounce(currentRayOrigin, currentRayDir, hitNormal, travelDistance);

        outHitPointBeforeEpsilon = hitPoint;
        outAccumulatedT = travelDistance;
        outPosition = hitPoint;
        outNormal = hitNormal;
        debugCoord_tex = vec3(ivec3(floor(hitPoint)) - worldOffset);
        outHitBlockCenter = vec3(ivec3(floor(hitPoint))) + 0.5;

        if (length(hitNormal) < 0.1) {
            bounceStatus = 0.0;
            debugCode = 0.0;
            break;
        }

        // El DDA ahora maneja correctamente la auto-intersección,
        // por lo que el `debugCode = 2.0` no debería aparecer.
        bounceStatus = 1.0;
        debugCode = float(bounceNum + 1);

        currentRayOrigin = hitPoint + hitNormal * epsilon;
        currentRayDir = reflect(currentRayDir, hitNormal);
    }
}