#version 330 core

layout(location = 0) in vec3 aPos; // La posición del punto que vamos a dibujar

uniform mat4 modelViewProjectionMatrix;
uniform sampler3D worldTexture;
uniform ivec3 worldOffset;

out vec4 vColor; // Color que pasaremos al fragment shader

void main() {
    // La posición del punto en el mundo es la que le pasamos desde Java
    vec3 worldPos = aPos;

    // Calculamos a qué coordenada de la textura corresponde
    ivec3 texCoord = ivec3(floor(worldPos)) - worldOffset;

    // Leemos la textura en esa coordenada
    float solidValue = texelFetch(worldTexture, texCoord, 0).r;

    // Si el valor del canal rojo es > 0.5, el bloque es sólido (lo pintamos de rojo).
    // Si no, es aire (lo pintamos de verde).
    if (solidValue > 0.5) {
        vColor = vec4(1.0, 0.0, 0.0, 1.0); // Rojo opaco
    } else {
        vColor = vec4(0.0, 1.0, 0.0, 0.5); // Verde semi-transparente
    }

    gl_Position = modelViewProjectionMatrix * vec4(worldPos, 1.0);
    gl_PointSize = 5.0; // Hacemos los puntos grandes para que se vean bien
}