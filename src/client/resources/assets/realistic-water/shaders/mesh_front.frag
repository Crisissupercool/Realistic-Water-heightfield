#version 450
#include "common.glsl"
layout(std430, binding = 3) readonly buffer ViewState { vec4 viewState; };
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in float vWall;
layout(location = 4) in float vAer;
layout(location = 0) out vec4 outG;
layout(location = 1) out float outMat;

void main() {
    float dist = -vView.z;
    if (dist < G.surf.z) {
        discard;
    }
    if (viewState.x > 0.5 && vWall > 0.5) {
        discard;
    }
    outG = vec4(normalize(vNormal), dist);
    // Aeration rides in the material id below the 0.25 tolerance composite.frag allows (0 for the fluid meshes):
    // surfaces 0..0.1, falling sheets (aerated walls) 0.12..0.22.
    float aer = clamp(vAer, 0.0, 1.0);
    outMat = G.matA.x + (vWall > 0.5 && aer > 0.005 ? 0.12 + 0.1 * aer : 0.1 * aer);
}
