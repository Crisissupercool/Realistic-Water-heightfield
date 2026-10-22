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
layout(location = 12) in vec2 vFlow;
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
    if (vBilinear > 0.5) {
        // Heightfield water: aeration, the falling-sheet flag and the water velocity are packed into the mantissa of a value in
        // [0.125, 0.25), inside the 0.25 tolerance composite.frag allows for water (the fluid meshes write exactly 0):
        // bits 0-5 aeration, 6 sheet, 7-14 flow direction, 15-22 flow speed (0..8 blocks/s).
        aer = clamp(aer, 0.0, 1.0);
        bool sheet = vWall > 0.5 && aer > 0.005;
        float speed = min(length(vFlow), 8.0);
        float angle = speed > 1.0e-3 ? atan(vFlow.y, vFlow.x) : 0.0;
        uint code = uint(aer * 63.0 + 0.5) | (sheet ? 64u : 0u) | (uint(fract(angle / 6.2831853 + 1.0) * 255.0 + 0.5) << 7)
            | (uint(speed / 8.0 * 255.0 + 0.5) << 15);
        outMat = uintBitsToFloat((124u << 23) | code);
    } else {
        outMat = G.matA.x;
    }
}
