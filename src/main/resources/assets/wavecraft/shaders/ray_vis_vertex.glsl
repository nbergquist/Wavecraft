#version 330 core

layout(location = 0) in vec3 pathPosition;
layout(location = 1) in float bounceStatus;

out vec3 fragPosition;
out float fragBounceStatus;

void main() {
    gl_Position = vec4(pathPosition, 1.0);
    fragPosition = pathPosition;
    fragBounceStatus = bounceStatus;
}
