#version 450
#include "common.glsl"
layout(binding = 2) uniform sampler2D sceneDepth;
layout(binding = 3) uniform sampler2D gbuffer;
layout(std430, binding = 4) readonly buffer ViewState { vec4 viewState; };
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec3 vCenter;
layout(location = 2) in float vRadius;
layout(location = 3) in vec4 vData;
layout(location = 0) out vec4 outFoam;

// View-space distance of the scene at screen point uv. The level projection includes view bobbing (a small rotation and
// shift), so depth must be unprojected at the pixel itself: reading it at the screen centre was off by a few percent toward
// the screen edges, swinging with every bob, which made thickness, refraction and reflections jump while walking.
float linearDepth(vec2 uv, float device) {
    vec4 v = G.invProj * vec4(uv * 2.0 - 1.0, device, 1.0);
    return -v.z / v.w;
}

void main() {
    float r2 = dot(vUV, vUV);
    if (r2 > 1.0) {
        discard;
    }
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
    float myDist = -vCenter.z - vRadius * sqrt(1.0 - r2);
    if (myDist > linearDepth(uv, texture(sceneDepth, uv).r)) {
        discard;
    }
    float type = vData.y;
    float a = vData.z;
    vec3 color = vec3(1.0);
    if (type >= 3.5) {
        // Splash water drops (heightfield): a crisp edge (anti-aliased over about a pixel), a bright core and a slightly darker,
        // bluer rim where light bends around the drop's edge. A soft gaussian here read as blur (scratchpad preview/drops.py).
        float r = sqrt(r2);
        float aa = max(fwidth(r), 1.0e-3);
        a *= 1.5 * (1.0 - smoothstep(1.0 - 1.5 * aa, 1.0, r));
        color = mix(vec3(0.97, 0.99, 1.0), vec3(0.62, 0.74, 0.86), smoothstep(0.35, 0.95, r));
    } else if (type > 2.5) {
        // Mist (heightfield spray): soft and faint, its alpha set in particle.vert.
        a *= exp(-4.0 * r2);
        color = vec3(0.9, 0.95, 1.0);
    } else if (type < 0.5) {
        a *= G.foamA.w;
        color = vec3(0.85, 0.92, 1.0);
    } else if (type > 1.5) {
        a *= G.foamA.z;
        color = vec3(0.8, 0.9, 1.0);
    } else {
        a *= 0.9 * clamp(vData.x * 0.8, 0.0, 1.0) * exp(-2.5 * r2);
    }
    float fd = texture(gbuffer, uv).w;
    if (viewState.x > 0.5) {
        if (type > 0.5 && type < 1.5) {
            a *= 0.15;
        }
        a *= exp(-myDist * G.scatter.w * G.misc.x * 2.0);
    } else if (fd > 0.0 && myDist > fd + 0.08) {
        a *= exp(-(myDist - fd) * 1.8);
    }
    outFoam = vec4(color * a, a);
}
