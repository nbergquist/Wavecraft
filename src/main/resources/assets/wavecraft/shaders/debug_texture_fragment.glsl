#version 330 core

in vec4 vColor; // Recibe el color del vertex shader
out vec4 FragColor;

void main() {
    FragColor = vColor; // Simplemente pinta el color que hemos calculado
}