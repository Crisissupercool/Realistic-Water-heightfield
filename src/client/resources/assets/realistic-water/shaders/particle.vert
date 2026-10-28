#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Parts { Particle parts[]; };
#ifdef DROPLETS
layout(std430, binding = 2) readonly buffer Dens { float dens[]; };
#endif
layout(std430, binding = VIEW_BINDING) readonly buffer ViewState { vec4 viewState; };
layout(push_constant) uniform PC { vec4 a; vec4 b; } pc;

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec3 vCenter;
layout(location = 2) out float vRadius;
layout(location = 3) out vec4 vData;

const vec2 CORNERS[6] = vec2[](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));

void main() {
    uint id = uint(gl_InstanceIndex);
    Particle p = parts[id];
#ifdef DROPLETS
    if (p.pos.w < 0.0) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
    ivec3 fp = ivec3(floor(p.pos.xyz * float(G.fine.w)));
    float nearDens = 0.0;
    for (int k = 0; k < 27; k++) {
        ivec3 q = fp + ivec3(k % 3, (k / 3) % 3, k / 9) - 1;
        if (inFine(q)) {
            nearDens = max(nearDens, dens[fineIndex(q)]);
        }
    }
    if (nearDens > G.surf.w || viewState.x > 0.5) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
#endif
    vec3 rel = G.domainOrigin.xyz + p.pos.xyz * G.domainOrigin.w;
    vec3 center = (G.view * vec4(rel, 1.0)).xyz;
    float r = pc.a.x;
#ifndef DROPLETS
    bool foam = p.vel.w > 0.5 && p.vel.w < 1.5;
    if (foam) {
        r *= pc.b.x;
    }
    // Mist puffs (heightfield spray, type 3): a radius of their own (pc.b.y blocks) and a faint alpha (pc.b.z) that fades out.
    bool mist = p.vel.w > 2.5 && p.vel.w < 3.5;
    if (mist) {
        r = pc.b.y;
    }
    // Splash water drops (heightfield, 4 + their size as a multiple of pc.b.w blocks).
    bool drop = p.vel.w >= 3.5;
    if (drop) {
        r = pc.b.w * mod(p.vel.w - 4.0, 16.0);
    }
#else
    bool foam = false;
    bool mist = false;
    bool drop = false;
#endif
    float alpha = mist ? pc.b.z * clamp(p.pos.w, 0.0, 1.0) : 1.0;
    float dist = -center.z;
    if (dist < pc.a.z) {
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }
    float pixelR = r * G.proj[1][1] * 0.5 * G.screen.y / dist;
    if (pixelR < pc.a.y) {
        float s = pc.a.y / max(pixelR, 1e-4);
        alpha *= 1.0 / (s * s);
        r *= s;
        pixelR = pc.a.y;
    } else if (pc.a.w > 0.0 && pixelR > pc.a.w && !foam && !mist && !drop) {
        r *= pc.a.w / pixelR;
    } else if (drop) {
        // Real splash drops are millimetres, so even right next to the camera they stay specks: a drop stops growing at a few
        // pixels, the cap set by its own size so a near splash keeps its mix of big and small drops. A 12 px cap for every drop
        // made a near splash a heap of equal white ovals ("rice"; scratchpad preview/drops7.py).
        float cap = 5.0 * pow(clamp(mod(p.vel.w - 4.0, 16.0) / 1.5, 0.0, 1.0), 1.5);
        if (pixelR > cap) {
            r *= cap / pixelR;
            pixelR = cap;
        }
    }
    if (pc.a.w > 0.0) {
        alpha *= smoothstep(pc.a.z, pc.a.z + (drop ? 2.0 : 1.2), dist);
    }
    vec2 c = CORNERS[gl_VertexIndex];
    vec2 offset = c * r;
#ifndef DROPLETS
    // Heightfield spray and drops (pc.b.w > 0) are drawn with a little motion blur: stretched along their velocity across the
    // screen by the distance they move in 12 ms, at most 4x (more read as blurry streaks). Drops a few pixels wide stay nearly
    // round (at most 1.6x): wide streaks are what read as grains; thin streaks only come from small, distant drops.
    if (pc.b.w > 0.0 && !mist) {
        vec2 sv = (mat3(G.view) * (p.vel.xyz * G.domainOrigin.w)).xy;
        float sl = length(sv);
        if (sl > 1.0e-3) {
            vec2 ax = sv / sl;
            float maxStretch = drop ? mix(3.0, 0.6, smoothstep(1.5, 4.0, pixelR)) : 3.0;
            float stretch = 1.0 + min(sl * 0.012 / max(r, 1.0e-4), maxStretch);
            offset = (ax * c.x * stretch + vec2(-ax.y, ax.x) * c.y) * r;
            alpha *= pow(stretch, -0.3);
        }
    }
#endif
    gl_Position = G.proj * vec4(center + vec3(offset, 0.0), 1.0);
    vUV = c;
    vCenter = center;
    vRadius = r;
    vData = vec4(p.pos.w, p.vel.w, alpha, 0.0);
}
