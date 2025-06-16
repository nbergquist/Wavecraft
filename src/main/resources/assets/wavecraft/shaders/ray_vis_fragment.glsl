#version 330 core

in vec3 fragPosition;
in float fragBounceStatus;

out vec4 fragColor;

void main() {
    if (fragBounceStatus < 0.0) {
        fragColor = vec4(1.0, 1.0, 0.0, 1.0); // Amarillo: colisión consigo mismo o redundante
    } else if (fragBounceStatus == 0.0) {
        fragColor = vec4(1.0, 0.0, 0.0, 1.0); // Rojo: sin colisión (ray escaped)
    } else {
        fragColor = vec4(1.0, 1.0, 1.0, 1.0); // Blanco: rebote válido
    }
}