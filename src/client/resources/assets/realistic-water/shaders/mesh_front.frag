#version 450
#include "common.glsl"
layout(std430, binding = 3) readonly buffer ViewState { vec4 viewState; };
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in float vWall;
layout(location = 4) in float vAer;
layout(location = 5) flat in vec3 vN0;
layout(location = 6) flat in vec3 vN1;
layout(location = 7) flat in vec3 vN2;
layout(location = 8) flat in vec3 vN3;
layout(location = 9) flat in vec4 vAer4;
layout(location = 10) in vec2 vQuadUV;
layout(location = 11) flat in float vBilinear;
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
    vec3 n = vNormal;
    float aer = vAer;
    if (vBilinear > 0.5) {
        // Heightfield quad: blend the corner values bilinearly (see mesh.vert).
        vec2 t = vQuadUV;
        n = mix(mix(vN0, vN1, t.x), mix(vN3, vN2, t.x), t.y);
        aer = mix(mix(vAer4.x, vAer4.y, t.x), mix(vAer4.w, vAer4.z, t.x), t.y);
    }
    outG = vec4(normalize(n), dist);
    // Aeration rides in the material id below the 0.25 tolerance composite.frag allows (0 for the fluid meshes):
    // surfaces 0..0.1, falling sheets (aerated walls) 0.12..0.22.
    aer = clamp(aer, 0.0, 1.0);
    outMat = G.matA.x + (vWall > 0.5 && aer > 0.005 ? 0.12 + 0.1 * aer : 0.1 * aer);
}
