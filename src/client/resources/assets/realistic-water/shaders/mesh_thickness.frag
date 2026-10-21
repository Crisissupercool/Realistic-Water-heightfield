#version 450
#include "common.glsl"
layout(std430, binding = 3) readonly buffer ViewState { vec4 viewState; };
layout(binding = 4) uniform sampler2D sceneDepth;
layout(location = 0) in vec3 vView;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in float vWall;
layout(location = 3) in float vOpen;
layout(location = 0) out float outThickness;

// View-space distance of the scene at screen point uv. The level projection includes view bobbing (a small rotation and
// shift), so depth must be unprojected at the pixel itself: reading it at the screen centre was off by a few percent toward
// the screen edges, swinging with every bob, which made thickness, refraction and reflections jump while walking.
float linearDepth(vec2 uv, float device) {
    vec4 v = G.invProj * vec4(uv * 2.0 - 1.0, device, 1.0);
    return -v.z / v.w;
}

void main() {
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
    float dist = min(-vView.z, linearDepth(uv, texture(sceneDepth, uv).r));
    vec3 gn = cross(dFdx(vView), dFdy(vView));
    if (dot(gn, vNormal) < 0.0) {
        gn = -gn;
    }
    bool entering = dot(gn, -vView) > 0.0;
    if (vOpen > 0.5) {
        // Open (heightfield) shape: every face is measured from the scene depth, so an entry with no matching exit is
        // closed by the terrain behind it, while entry/exit pairs still cancel the offset. Capped to keep R32F precise.
        float scene = min(linearDepth(uv, texture(sceneDepth, uv).r), 512.0);
        float d = min(dist, scene);
        outThickness = entering ? scene - d : d - scene;
        return;
    }
    outThickness = entering ? -dist : dist;
}
