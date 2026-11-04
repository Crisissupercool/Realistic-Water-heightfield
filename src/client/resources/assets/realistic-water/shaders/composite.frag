#version 450
#include "common.glsl"
layout(binding = 1) uniform sampler2D gbuffer;
layout(binding = 2) uniform sampler2D thicknessTex;
layout(binding = 3) uniform sampler2D sceneColor;
layout(binding = 4) uniform sampler2D sceneDepth;
layout(binding = 5) uniform sampler2D foamTex;
layout(binding = 6) uniform sampler2D materialTex;
layout(std430, binding = 7) readonly buffer ViewState { vec4 viewState; };
layout(std430, binding = 8) readonly buffer SlimeTex { int slimeTex[]; };
#define LIGHTMAP_BINDING 9
#include "fluid_light.glsl"
layout(binding = 10) uniform sampler2D foamSurfaceTex;
layout(std430, binding = 11) readonly buffer SeaColors { uint seaColors[]; };
// Points where the player disturbed heightfield water, two vec4 each: ((x, z) in wrapped world blocks, seconds since, displaced
// volume in blocks^3), (foam it churned up, 0, 0, 0).
layout(std430, binding = 12) readonly buffer Wake { vec4 wake[]; };
// Readable area, material filter, sea pass, background pass, layer debug, sea colour map; then the player's wake (HfRipples):
// the drawn water level it lies on, normal gain, number of entries (0 = off), wave life (s), foam strength, and the wake only
// exists within wakeRadius of wakeCenter (wrapped world xz); last WaterSettings.hfCompositeOff, for measuring what each part
// costs: 1 no reflection trace, 2 no ripples, 4 no foam, 8 no wake, 32 refraction only, 64 reflection only.
layout(push_constant) uniform Push {
    vec4 area;
    float filterMat;
    float sea;
    float background;
    float debug;
    float colorMap;
    float wakeY;
    float wakeGain;
    int wakeCount;
    float wakeLife;
    float wakeFoam;
    vec2 wakeCenter;
    float wakeRadius;
    int debugOff;
} pc;
#define SEA_COLOR_CELLS 512
const vec3 OCEAN_WATER = vec3(63.0, 118.0, 228.0) / 255.0;
const float VANILLA_WATER_TEXTURE = 0.694;

bool readable(vec2 uv) {
    return all(greaterThanEqual(uv, pc.area.xy)) && all(lessThanEqual(uv, pc.area.zw));
}

float thicknessAt(vec2 uv) {
    return readable(uv) ? texture(thicknessTex, uv).r : 0.0;
}
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265;
const float SPECULAR_CAP = 30.0;

// Shader-pack layer output (G.compat.x) shades every pixel twice, over black and over white (gBackground 0 and 1), to get its
// colour and opacity; -1 means the scene behind is read from the screen.
float gBackground = -1.0;
float gDepth = 0.0;
vec3 gScatter = vec3(0.0);
vec3 gAbsorb = vec3(0.0);
vec3 gSeaWater = vec3(-1.0);
float gSeaRough = 0.0;

vec3 unpackRgb(uint v) {
    return vec3((v >> 16) & 0xFFu, (v >> 8) & 0xFFu, v & 0xFFu) / 255.0;
}

vec4 seaColorAt(vec3 rel) {
    vec2 local = vec2(G.dims.xz & 3) + G.camGrid.xz + rel.xz;
    vec2 f = local * 0.25 - 0.5;
    vec2 fl = floor(f);
    vec2 t = f - fl;
    ivec2 base = (G.dims.xz >> 2) + ivec2(fl);
    vec4 sum = vec4(0.0);
    for (int k = 0; k < 4; k++) {
        ivec2 c = base + ivec2(k & 1, k >> 1);
        uint v = seaColors[(c.x & (SEA_COLOR_CELLS - 1)) + (c.y & (SEA_COLOR_CELLS - 1)) * SEA_COLOR_CELLS];
        uint tag = 0x80u | uint((c.x >> 9) & 7) | uint((c.y >> 9) & 7) << 3;
        if ((v >> 24) == tag) {
            float w = ((k & 1) != 0 ? t.x : 1.0 - t.x) * ((k >> 1) != 0 ? t.y : 1.0 - t.y);
            sum += vec4(unpackRgb(v) * w, w);
        }
    }
    return sum.w > 1e-4 ? vec4(sum.rgb / sum.w, sum.w) : vec4(0.0);
}

void seaLook(vec3 rel) {
    gScatter = G.scatter.rgb;
    gAbsorb = G.absorb.rgb;
    gSeaWater = vec3(-1.0);
    if (pc.sea < 0.5 || pc.colorMap < 0.5) {
        return;
    }
    vec4 c = seaColorAt(rel);
    if (c.w <= 0.0) {
        return;
    }
    gSeaWater = c.rgb;
    vec3 ratio = clamp(c.rgb / OCEAN_WATER, vec3(0.25), vec3(4.0));
    gScatter *= ratio;
    gAbsorb /= ratio;
}

vec3 behindAt(vec2 uv) {
    return gBackground < 0.0 ? texture(sceneColor, uv).rgb : vec3(gBackground);
}

// View-space distance of the scene at screen point uv, unprojected at the pixel itself so it stays exact whatever the projection
// holds (the view bobbing used to be in it, which made the screen-centre form a few percent off toward the edges).
float linearDepth(vec2 uv, float device) {
    vec4 v = G.invProj * vec4(uv * 2.0 - 1.0, device, 1.0);
    return -v.z / v.w;
}

// Point at view-space distance dist along the camera ray through screen point uv, rebuilt from two points unprojected at
// different depths, so it is exact even when the ray does not pass through the view-space origin.
vec3 viewPosAt(vec2 uv, float dist) {
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 a = G.invProj * vec4(ndc, 0.2, 1.0);
    vec4 b = G.invProj * vec4(ndc, 0.8, 1.0);
    vec3 p0 = a.xyz / a.w;
    vec3 p1 = b.xyz / b.w;
    vec3 ray = p1 - p0;
    return p0 + ray * ((-dist - p0.z) / ray.z);
}

// Unit vector from the water point at uv back toward the eye. The depth buffer is reversed (near = 1), so which unprojected point
// is nearer is decided by distance, not by depth value.
vec3 toEyeAt(vec2 uv) {
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 a = G.invProj * vec4(ndc, 0.2, 1.0);
    vec4 b = G.invProj * vec4(ndc, 0.8, 1.0);
    vec3 pa = a.xyz / a.w;
    vec3 pb = b.xyz / b.w;
    return -pa.z < -pb.z ? normalize(pa - pb) : normalize(pb - pa);
}

// World position (camera-relative world axes plus the wrapped camera position) of a view-space point. The view matrix carries
// the view bobbing's shift, so points need w = 1.
vec3 worldAt(vec3 viewPos) {
    return G.misc.yzw + (G.invView * vec4(viewPos, 1.0)).xyz;
}

float pixelFootprint(float dist) {
    return 2.0 * dist / max(G.proj[1][1] * G.screen.y, 1e-4);
}

vec3 skyColor(vec3 dirWorld) {
    float t = dirWorld.y;
    vec3 horizon = G.fogColor.rgb;
    vec3 zenith = G.skyColor.rgb;
    if (t >= 0.0) {
        return mix(horizon, zenith, smoothstep(0.0, 0.45, t));
    }
    return horizon * mix(0.9, 0.35, smoothstep(0.0, 0.35, -t));
}

bool projectToScreen(vec3 pos, out vec2 suv) {
    vec4 clip = G.proj * vec4(pos, 1.0);
    if (clip.w <= 0.0) {
        return false;
    }
    suv = clip.xy / clip.w * 0.5 + 0.5;
    return all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0)));
}

// What a reflected ray from P that the trace did not resolve sees: the scene far away along it, read off the screen, else the
// sky. The bare sky colour is the brightest there is and stood out between traced reflections. Parallax-corrected: the point
// 32 blocks along the ray from P is projected, the scene's distance there taken, and the point that far along the ray projected
// again. Taken from the camera instead (the scene in the ray's direction, like an environment map) the reflection slid with
// the camera: jumping moved the mirrored shore across the water. A ray turned down (off a wave's face tilted toward the
// camera) would meet the water itself; the sky below the horizon is grey, so it takes the sky mirrored up instead (grey
// patches on wakes and waves). In shader-pack layer output the screen is not the final scene, so only the sky is used.
vec3 distantReflection(vec3 P, vec3 Rv, vec3 Rw) {
    vec3 up = normalize(vec3(Rw.x, max(abs(Rw.y), 0.02), Rw.z));
    vec3 sky = skyColor(up);
    if (gBackground >= 0.0) {
        return sky;
    }
    if (Rw.y < 0.0) {
        Rv = mat3(G.view) * up;
    }
    vec2 duv;
    if (!projectToScreen(P + Rv * 32.0, duv)) {
        return sky;
    }
    float d = length(viewPosAt(duv, linearDepth(duv, texture(sceneDepth, duv).r)) - P);
    if (!projectToScreen(P + Rv * clamp(d, 8.0, 512.0), duv)) {
        return sky;
    }
    float far = smoothstep(8.0, 32.0, linearDepth(duv, texture(sceneDepth, duv).r));
    vec2 edge = smoothstep(vec2(0.0), vec2(0.08), duv) * (1.0 - smoothstep(vec2(0.92), vec2(1.0), duv));
    return mix(sky, texture(sceneColor, duv).rgb, far * edge.x * edge.y);
}

// Screen-space reflection. The coarse march finds the first step behind the scene, then a binary search pins the crossing
// down: without it a ray that overshoots by up to a step "hits" whatever lies there (jagged teeth of a cliff in the water next
// to it, reflections jumping with every small camera move). A crossing far behind the surface (the ray passed behind an
// object, such as a bank on the way to the trees beyond) is no hit, and the march goes on until the ray is in front of the
// scene again (up to 4 searched). The scene depth has no water, so only the scene on the reflecting side of the water's level
// can be hit (a level plane through planeP; planeN is view-space up, down from below, or zero for a falling sheet: no test),
// not the ground under the water. Rays heading back toward the camera and long rays fade out. Nothing is traced in shader-pack
// layer output (the screen there is not the final scene).
vec3 traceReflection(vec3 origin, vec3 R, vec3 planeP, vec3 planeN, out float hit, out float travelled) {
    hit = 0.0;
    travelled = 0.0;
    if (gBackground >= 0.0) {
        return vec3(0.0);
    }
    vec3 pos = origin;
    vec3 last = origin;
    float stepLen = 0.15;
    vec2 suv;
    int crossings = 0;
    bool inFront = true;
    for (int i = 0; i < 48; i++) {
        last = pos;
        pos += R * stepLen;
        if (!projectToScreen(pos, suv)) {
            break;
        }
        float sceneDist = linearDepth(suv, texture(sceneDepth, suv).r);
        // Still behind the object a crossing was rejected for: march on until the ray is in front of the scene again.
        bool wasInFront = inFront;
        inFront = -pos.z <= sceneDist;
        if (!inFront && !wasInFront) {
            stepLen *= 1.1;
            continue;
        }
        if (-pos.z > sceneDist && dot(viewPosAt(suv, sceneDist) - planeP, planeN) < -0.05) {
            // Behind ground on the other side of the surface (under water seen from above, above it from below): not a hit.
            stepLen *= 1.1;
            continue;
        }
        if (-pos.z > sceneDist) {
            if (crossings++ >= 4) {
                break;
            }
            vec3 a = last;
            vec3 b = pos;
            for (int j = 0; j < 6; j++) {
                vec3 m = 0.5 * (a + b);
                vec2 muv;
                if (!projectToScreen(m, muv)) {
                    break;
                }
                if (-m.z > linearDepth(muv, texture(sceneDepth, muv).r)) {
                    b = m;
                } else {
                    a = m;
                }
            }
            if (!projectToScreen(b, suv)) {
                break;
            }
            float behind = -b.z - linearDepth(suv, texture(sceneDepth, suv).r);
            if (behind > 0.35 + 0.05 * -b.z) {
                stepLen *= 1.1;
                continue;
            }
            vec2 edge = smoothstep(vec2(0.0), vec2(0.08), suv) * (1.0 - smoothstep(vec2(0.92), vec2(1.0), suv));
            float travel = 1.0 - smoothstep(0.6, 1.0, float(i) / 48.0);
            float away = 1.0 - smoothstep(0.1, 0.5, R.z);
            hit = edge.x * edge.y * travel * away;
            travelled = length(b - origin);
            return texture(sceneColor, suv).rgb;
        }
        stepLen *= 1.1;
    }
    return vec3(0.0);
}

float noiseHash(vec2 c) {
    return float(hash(uint(int(c.x)) * 73856093u ^ uint(int(c.y)) * 19349663u)) * (1.0 / 4294967295.0);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(noiseHash(i), noiseHash(i + vec2(1.0, 0.0)), f.x), mix(noiseHash(i + vec2(0.0, 1.0)), noiseHash(i + 1.0), f.x), f.y);
}

// Ripple slope the pixel cannot resolve (its RMS), from the last rippleGradient call: the sea turns it into roughness.
float gRippleLost = 0.0;

// Small wind ripples: 12 directional waves with scattered directions, phases and deep-water speeds, their strength varied over
// the surface by slow noise and their crests bent by a static warp, so the pattern does not repeat (5 fixed waves read as
// regular stripes). Short waves spread wider around the wind direction, like real wind ripples.
vec2 rippleGradient(vec2 p, float t, float footprint) {
    vec2 g = vec2(0.0);
    vec2 q = p + 0.6 * vec2(valueNoise(p * 0.21), valueNoise(p * 0.21 + 5.2));
    float patchA = 0.3 + 1.4 * valueNoise(p * 0.03 + vec2(t * 0.01, 3.7));
    float patchB = 0.3 + 1.4 * valueNoise(p * 0.07 + vec2(9.1, t * 0.015));
    float freq = 0.9;
    float lostVar = 0.0;
    for (int i = 0; i < 12; i++) {
        float lambda = 6.2831853 / freq;
        float fade = smoothstep(4.0, 12.0, lambda / max(footprint, 1e-5));
        float w = 0.1015 * pow(freq, -0.15) * ((i & 1) == 0 ? patchA : patchB);
        lostVar += (1.0 - fade * fade) * w * w;
        if (fade > 0.0) {
            // Directions step by the golden angle within a spread that widens for shorter waves, so no two neighbors line up.
            float h2 = fract(sin(float(i) * 78.233 + 1.7) * 43758.5453);
            float ang = 0.5 + (fract(float(i) * 0.618034 + 0.31) - 0.5) * 2.0 * (0.9 + 0.8 * float(i) / 11.0);
            vec2 dir = vec2(cos(ang), sin(ang));
            float phase = dot(q, dir) * freq + t * sqrt(freq) * 1.1 + h2 * 6.2831853;
            g += dir * (w * fade * cos(phase));
        }
        freq *= 1.31;
    }
    gRippleLost = sqrt(lostVar);
    return g;
}

// Flow mapping: two copies of a pattern carried along by the water, each restarted every cycle, half a cycle apart, and blended
// by a triangle weight that is zero when a copy restarts, so the pattern moves with the current without ever visibly jumping.
// The cycle's phase varies slowly over the surface: in step everywhere, the whole stream's foam and ripples cross-faded to
// a new copy at the same moment twice a cycle, which on fast water read as the pattern resetting.
const float FLOW_CYCLE = 1.6;
// Speeds (blocks/s) over which the still pattern gives way to the flow-mapped one.
const vec2 FLOW_BLEND = vec2(0.03, 0.15);
// Most reflection the player's wake may add to water seen from above (see shadeAboveWater).
const float WAKE_FRESNEL = 0.12;

void flowPhases(vec2 p, vec2 flow, out vec2 off0, out vec2 off1, out float w0) {
    float t = G.simB.z / FLOW_CYCLE + valueNoise(p * 0.11);
    float p0 = fract(t);
    float p1 = fract(t + 0.5);
    off0 = flow * (p0 - 0.5) * FLOW_CYCLE;
    off1 = flow * (p1 - 0.5) * FLOW_CYCLE;
    w0 = 1.0 - abs(2.0 * p0 - 1.0);
}

// flow: water velocity (blocks/s, world x/z) for heightfield water, so the ripples drift with rivers and streams.
vec3 rippleNormal(vec3 nView, vec3 viewPos, float footprint, vec2 flow) {
    gRippleLost = 0.0;
    if (G.shadeA.x <= 0.0) {
        return nView;
    }
    vec3 nWorld = normalize(mat3(G.invView) * nView);
    vec3 world = worldAt(viewPos);
    float scale = max(G.shadeA.y, 1e-3);
    // At grazing angles a pixel covers far more surface along the view direction than its distance alone implies;
    // without this the finer ripple bands alias into moire rings on large flat water.
    float grazing = max(abs(dot(nView, normalize(-viewPos))), 0.05);
    float t = G.simB.z * G.shadeA.z;
    float fp = footprint * scale / grazing;
    // Still and flow-mapped ripples blend over FLOW_BLEND instead of switching at one speed: crossing it, the pattern changed
    // all at once (the ripples and foam of slowing or speeding water seemed to reset).
    float speed = length(flow);
    float flowing = smoothstep(FLOW_BLEND.x, FLOW_BLEND.y, speed);
    vec2 grad = vec2(0.0);
    if (flowing < 1.0) {
        grad = rippleGradient(world.xz * scale, t, fp) * (1.0 - flowing);
    }
    if (flowing > 0.0) {
        vec2 off0;
        vec2 off1;
        float w0;
        flowPhases(world.xz, flow, off0, off1, w0);
        vec2 moving = rippleGradient((world.xz - off0) * scale, t, fp) * w0
            + rippleGradient((world.xz - off1) * scale + vec2(37.1, 11.3), t, fp) * (1.0 - w0);
        // Faster water is rougher.
        grad += moving * (1.0 + 0.12 * min(speed, 6.0)) * flowing;
    }
    vec3 g = vec3(grad.x, 0.0, grad.y) * G.shadeA.x;
    return normalize(mat3(G.view) * normalize(nWorld - (g - nWorld * dot(g, nWorld))));
}

vec3 rippleNormal(vec3 nView, vec3 viewPos, float footprint) {
    return rippleNormal(nView, viewPos, footprint, vec2(0.0));
}

vec3 sunSpecular(vec3 n, vec3 V, float footprint, float extraRough) {
    vec3 L = normalize(G.sunDir.xyz);
    float NoL = dot(n, L);
    if (NoL <= 0.0 || G.sunDir.w <= 0.0) {
        return vec3(0.0);
    }
    vec3 H = normalize(L + V);
    float NoH = max(dot(n, H), 0.0);
    float NoV = max(dot(n, V), 1e-3);
    float base = G.shadeA.w + footprint * 0.25;
    float rough = clamp(extraRough > 0.0 ? sqrt(base * base + extraRough * extraRough) : base, 0.02, 0.7);
    float a2 = rough * rough * rough * rough;
    float den = NoH * NoH * (a2 - 1.0) + 1.0;
    float D = a2 / max(PI * den * den, 1e-9);
    float F = 0.02 + 0.98 * pow(1.0 - max(dot(V, H), 0.0), 5.0);
    float k = rough * rough * 0.5;
    float Vis = 0.5 / max(NoL * (NoV * (1.0 - k) + k) + NoV * (NoL * (1.0 - k) + k), 1e-5);
    return vec3(1.0, 0.96, 0.88) * min(D * Vis * F * NoL * G.shadeB.x, SPECULAR_CAP) * G.sunDir.w;
}

vec3 shadeAboveLava(vec2 uv, vec3 n, float fd, float ambient) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = toEyeAt(uv);
    n = dot(n, V) < 0.0 ? -n : n;
    float footprint = pixelFootprint(fd);
    vec3 wrinkled = rippleNormal(n, P, footprint);
    n = normalize(mix(n, wrinkled, smoothstep(0.0, 0.2, dot(wrinkled, V))));
    float path = max(thicknessAt(uv), 0.0) * G.waterA.y;
    vec3 glow = G.scatter.rgb * (1.4 + ambient);
    vec3 core = glow * (1.0 - exp(-G.scatter.w * path * 0.45));
    float fresnel = pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 2.0);
    vec3 rim = glow * fresnel * 0.85;
    return core + rim + sunSpecular(n, V, footprint, 0.0) * vec3(1.0, 0.65, 0.25);
}

float foamLace(vec2 p, float coverage, float footprint) {
    float size = max(G.shadeB.w, 1e-3);
    vec2 q = p / size;
    ivec2 cell = ivec2(floor(q));
    float f1 = 9.0;
    float f2 = 9.0;
    for (int k = 0; k < 9; k++) {
        ivec2 c = cell + ivec2(k % 3 - 1, k / 3 - 1);
        uint s = hash(uint(c.x) * 73856093u ^ uint(c.y) * 19349663u);
        vec2 feature = vec2(c) + 0.1 + 0.8 * vec2(rand(s), rand(s));
        float d = length(q - feature);
        if (d < f1) {
            f2 = f1;
            f1 = d;
        } else if (d < f2) {
            f2 = d;
        }
    }
    float wall = 1.0 - smoothstep(0.0, 0.06 + 0.6 * coverage, f2 - f1);
    float detail = smoothstep(0.7, 2.5, size / max(footprint, 1e-5));
    return mix(0.55, wall, detail);
}

const float WAKE_G = 9.81;
// Waves shorter than ~0.8 blocks die out (capillary and viscous losses). Each source has a size growing with its volume
// (a splash spreads over its crater, not a point), which softens the near field and the shortest waves it makes.
const float WAKE_KC = 8.0;
const float WAKE_CORE = 0.15;

// The player's wake and splash rings: the sum of deep-water ring waves from each point where the player disturbed the surface
// (the Cauchy-Poisson solution: a volume V released at a point gives eta(r, t) = V k / (pi r) cos(g t^2 / 4r), with local
// wavenumber k = g t^2 / 4r^2). Long waves outrun short ones, which draws the V of a Kelvin wake behind a moving body and turns
// a splash into a train of rings. Evaluated per pixel, so it stays sharp at any distance; nothing is simulated on a grid.
// Returns (d eta / dx, d eta / dz, eta, churned-water foam).
vec4 wakeAt(vec3 world, float footprint) {
    vec4 r = vec4(0.0);
    if (pc.wakeCount <= 0 || abs(world.y - pc.wakeY) > 0.6 || distance(world.xz, pc.wakeCenter) > pc.wakeRadius) {
        return r;
    }
    float trail = 0.0;
    for (int i = 0; i < pc.wakeCount; i++) {
        vec4 e = wake[i * 2];
        float churned = wake[i * 2 + 1].x;
        vec2 d = world.xz - e.xy;
        float d2 = dot(d, d);
        float tau = e.z;
        if (churned > 0.0 && d2 < 2.5) {
            trail += churned * exp(-d2 / (0.25 + 0.5 * churned)) * exp(-tau / 0.9);
        }
        // Nothing has arrived yet beyond the first wave (theta < 0.5), so skip before the expensive part.
        float reach = WAKE_G * tau * tau * 0.5;
        if (d2 > reach * reach) {
            continue;
        }
        float core = WAKE_CORE + 0.35 * sqrt(abs(e.w));
        float rr = sqrt(d2 + core * core);
        float th = WAKE_G * tau * tau / (4.0 * rr);
        if (th < 0.5) {
            continue;
        }
        float k = th / rr;
        // Fade waves past the cut-off, waves shorter than ~4 pixels (they would alias), old waves, and the leading edge.
        float kc = k / WAKE_KC;
        float ks = k * core;
        float env = exp(-kc * kc - 0.5 * ks * ks) * exp(-tau / max(pc.wakeLife, 0.1)) * clamp((th - 0.5) * 0.5, 0.0, 1.0)
            * smoothstep(3.0, 6.0, 6.2831853 / max(k * footprint, 1.0e-5));
        if (env < 1.0e-4) {
            continue;
        }
        float amp = e.w * k / (3.14159265 * rr) * env;
        r.z += amp * cos(th);
        r.xy += (amp * k * sin(th) / rr) * d;
    }
    r.w = 1.0 - exp(-trail * 6.0 * pc.wakeFoam);
    return r;
}

// The foam pattern before its threshold, and the coordinates it was sampled at (x: pattern, yz: warped coordinates, for the
// bubbles). Value noise octaves on coordinates warped by slow noise, so patches are organic rather than round blobs; the finer
// octaves only where a pixel resolves them. Foam on running water is stretched into streaks by averaging the two coarse
// octaves along the flow (smear: half the streak, in blocks), never by rotating the coordinates (rotating world coordinates by a
// direction that varies across the surface scrambled the pattern into scratchy lines wherever the flow turned).
vec3 foamField(vec2 p, vec2 smear, float footprint) {
    float t = G.simB.z;
    vec2 w = vec2(valueNoise(p * 0.6 + vec2(7.3, 0.0)), valueNoise(p * 0.6 + vec2(0.0, 3.1))) - 0.5;
    vec2 q = p + w * 0.9 + vec2(t * 0.07, -t * 0.05);
    float d1 = 1.0 - smoothstep(0.04, 0.15, footprint);
    vec2 a = q * 1.1;
    vec2 b = q * 2.3 + vec2(5.2, 0.0);
    float n1 = valueNoise(a);
    float n2 = valueNoise(b);
    float len = length(smear);
    if (len > 0.01) {
        // Averaging flattens the noise; its contrast is restored so the foam keeps its coverage.
        n1 = 0.5 + ((n1 + valueNoise(a + smear * 1.1) + valueNoise(a - smear * 1.1)) / 3.0 - 0.5) * (1.0 + 0.6 * min(len * 1.1, 1.0));
        n2 = 0.5 + ((n2 + valueNoise(b + smear * 2.3) + valueNoise(b - smear * 2.3)) / 3.0 - 0.5) * (1.0 + 0.6 * min(len * 2.3, 1.0));
    }
    float n3 = d1 > 0.0 ? valueNoise(q * 4.7 + vec2(0.0, 9.1)) : 0.5;
    float n4 = d1 > 0.0 ? valueNoise(q * 9.6 + vec2(1.7, 0.0)) : 0.5;
    return vec3(n1 * 0.5 + n2 * 0.27 + mix(0.5, n3, d1) * 0.15 + mix(0.5, n4, d1) * 0.08, q);
}

// Foam: patches of the field whose coverage grows with the foam amount, with a feathered edge (wider inward) so they fade into
// the water, and up close bubbly holes of varied size, more and bigger toward a patch's thin edge, filling in toward its middle.
// (A cell pattern here read as cracked tiles, round dots on flat white as a printed texture; see scratchpad preview/foam2.py.)
float foamShape(vec3 f, float coverage, float footprint) {
    float edge = 0.02 + 1.5 * footprint;
    float cover = smoothstep(1.0 - coverage - edge, 1.0 - coverage + 0.12 + edge, f.x);
    if (cover <= 0.0) {
        return 0.0;
    }
    float inside = smoothstep(1.0 - coverage + 0.05, 1.0 - coverage + 0.35, f.x);
    float base = mix(0.7, 1.0, inside);
    float detail = 1.0 - smoothstep(0.015, 0.06, footprint);
    if (detail <= 0.0) {
        return cover * base;
    }
    float h = valueNoise(f.yz * 13.0 + vec2(3.3, 0.0)) * 0.6 + valueNoise(f.yz * 29.0 + vec2(0.0, 8.8)) * 0.4;
    float holes = smoothstep(0.62 - 0.35 * inside, 0.72 - 0.3 * inside, h);
    float lace = mix(holes, 1.0, inside * inside) * mix(0.75, 1.0, inside);
    return cover * mix(base, lace, detail);
}

float foamTexture(vec2 p, float coverage, float footprint) {
    return foamShape(foamField(p, vec2(0.0), footprint), coverage, footprint);
}

// Foam carried by the current: flow-mapped like the ripples (two phases, see flowPhases) and stretched into streaks along
// fast water.
float flowFoam(vec2 p, vec2 flow, float coverage, float footprint) {
    float speed = length(flow);
    float flowing = smoothstep(FLOW_BLEND.x, FLOW_BLEND.y, speed);
    float still = flowing < 1.0 ? foamTexture(p, coverage, footprint) : 0.0;
    if (flowing <= 0.0) {
        return still;
    }
    vec2 off0;
    vec2 off1;
    float w0;
    flowPhases(p, flow, off0, off1, w0);
    vec2 smear = flow * (0.12 * min(speed, 6.0) / speed);
    vec2 a = p - off0;
    vec2 b = p - off1 + vec2(13.7, 5.1);
    float fa = foamShape(foamField(a, smear, footprint), coverage, footprint);
    float fb = foamShape(foamField(b, smear, footprint), coverage, footprint);
    return mix(still, mix(fb, fa, w0), flowing);
}

// Aerated water (heightfield only, aer from the material id): falling sheets get streaks running down them and a bumpy normal
// instead of the flat glass look; foam on the surface shows as soft patches. Ripples around the player bend the surface normal.
// A falling sheet's drop (blocks fallen from its edge) is known up to this; below it the sheet uses its own coordinates.
const float SHEET_NEAR = 0.5;
// How fast the pattern on falling sheets moves down (blocks/s).
const float SHEET_SCROLL = 5.0;
// Reflectance (Fresnel) below which the screen-space reflection is not traced (see shadeAboveWater).
const float SSR_MIN_F = 0.035;
const vec2 SHEET_OUTWARD[4] = vec2[](vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0));

// bs: block and sky light at the surface (material target gb), for the vanilla lightmap.
vec3 shadeAboveWater(vec2 uv, vec3 n, float fd, float sceneDist, float ambient, vec2 bs, float aer, bool sheet, vec2 flow, float sheetDrop) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = toEyeAt(uv);
    n = dot(n, V) < 0.0 ? -n : n;
    vec3 nGeo = n;
    float footprint = pixelFootprint(fd);
    vec3 world = worldAt(P);
    // Falling sheets: where one leaves its edge (lip = 1) it is shaded like the top surface it continues; its foam uses the
    // top surface's coordinates unrolled over the edge (world xz plus the drop, outward), so the foam that rode the current to
    // the edge pours on over the lip without a seam. Lower down it gets coordinates across and down the fall, streaks and a
    // bumpy normal.
    float lip = 1.0;
    vec2 nearP = world.xz;
    float sheetAcross = 0.0;
    float sheetDown = 0.0;
    if (sheet) {
        vec2 outward = length(flow) > 1.0e-3 ? normalize(flow) : vec2(1.0, 0.0);
        vec2 along = vec2(-outward.y, outward.x);
        lip = 1.0 - smoothstep(0.0, 0.3, sheetDrop);
        nearP = world.xz + outward * sheetDrop;
        // Coordinates across the fall and down it, scrolling down at one speed for every sheet (a speed varying down the sheet
        // would shear the pattern more and more over time).
        sheetAcross = dot(world.xz, along);
        sheetDown = dot(world.xz, outward) - world.y - G.simB.z * SHEET_SCROLL;
        // Streaks a few per block across bend the surface a little.
        float detail = smoothstep(1.0, 3.0, 0.3 / max(footprint, 1.0e-5)) * (1.0 - lip);
        float s1 = valueNoise(vec2(sheetAcross * 3.5, sheetDown * 0.6));
        vec3 side = mat3(G.view) * vec3(along.x, 0.0, along.y);
        n = normalize(n + side * ((s1 - 0.5) * 0.45 * detail));
    }
    float thickness = max(thicknessAt(uv), 0.0);
    vec3 upV = normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
    // The water's depth across its own face: the thickness along the view times |V.n| (a slab d deep is seen through d / |V.n|).
    // It was times |V.up|, near zero for any view near level whatever the face, so a side face (a tank's wall, water against a
    // bank) or a steep top (the ramp over a step) seen at eye level counted as a film and was bent to lie flat: level water seen
    // edge-on, all reflection, a bright band across the water exactly at eye height.
    float vertical = thickness * abs(dot(V, n));
    // Thin films lie flat; a falling sheet is thin too but must keep its own normal, and so must a side face: only faces within
    // about 55 degrees of level count.
    float film = sheet ? 0.0 : (1.0 - smoothstep(0.02, max(G.shadeB.z, 0.03), vertical)) * smoothstep(0.35, 0.6, abs(dot(n, upV)));
    n = normalize(mix(n, upV, film * 0.9));
    vec3 tilt = vec3(0.0);
    vec4 wk = vec4(0.0);
    if (!sheet && (pc.debugOff & 8) == 0) {
        wk = wakeAt(world, footprint);
        vec2 slope = wk.xy * pc.wakeGain;
        float steep = length(slope);
        slope *= steep > 0.9 ? 0.9 / steep : 1.0;
        wk.xy = slope;
        tilt = mat3(G.view) * vec3(slope.x, 0.0, slope.y);
        n = normalize(n - tilt);
    }
    float lostRough = 0.0;
    if ((pc.debugOff & 2) == 0) {
        vec3 wrinkled = rippleNormal(n, P, footprint, flow);
        n = normalize(mix(n, wrinkled, lip * smoothstep(0.0, 0.15, dot(wrinkled, V))));
        // The sea turns ripples too fine to resolve into roughness.
        lostRough = pc.sea > 0.5 ? length(vec2(gSeaRough, gRippleLost * G.shadeA.x)) : 0.0;
    }

    // Refraction shifts the scene behind by how far the normal leans away from vertical (flat water does not shift it), capped
    // on screen; the wake bends it half as much as it bends reflections, so the riverbed stays readable behind a swimmer.
    vec3 nRefract = normalize(n + 0.5 * tilt);
    vec2 bend = (nRefract - upV * dot(nRefract, upV)).xy;
    vec2 offset = bend * G.waterA.z * clamp(thickness, 0.0, 2.0) / max(fd, 2.0);
    float shift = length(offset);
    offset *= shift > 0.03 ? 0.03 / shift : 1.0;
    vec2 ruv = clamp(uv + offset, vec2(0.0), vec2(1.0));
    float refrDist = linearDepth(ruv, texture(sceneDepth, ruv).r);
    float path = max(thicknessAt(ruv), 0.0);
    if (refrDist < fd) {
        ruv = uv;
        path = thickness;
    }
    path *= G.waterA.y;
    vec3 under = behindAt(ruv);
    // Ground under water looks wet: darker and more saturated, fully from 3 mm of water. Judged by this pixel's own water, not by
    // the refracted path (the ripples move the refraction offset every frame, and at a thin film's edge the darkening flickered
    // between wet and dry); any water at all wets the ground, so the brink a stream thins to before it pours off has no dry band.
    // Only ground the water lies on: a sheet falling in front of a cliff, a block or more away, darkened the cliff into streaks.
    float wet = G.shadeB.y * smoothstep(0.0, 0.003, max(thickness * G.waterA.y, path)) * (1.0 - smoothstep(0.3, 1.0, refrDist - fd));
    under = pow(max(under, vec3(0.0)), vec3(1.0 + 1.5 * wet)) * (1.0 - 0.5 * wet);
    vec3 transmitted = under * exp(-gAbsorb * path);
    vec3 lightColor = vanillaLight(bs);
    vec3 scattered = gScatter * lightColor * (1.0 - exp(-G.scatter.w * path));
    vec3 water = transmitted + scattered;
    if (G.compat.w > 0.0) {
        vec3 tint = gSeaWater.r >= 0.0 ? gSeaWater * VANILLA_WATER_TEXTURE * lightColor
            : G.scatter.rgb / max(max(G.scatter.r, G.scatter.g), max(G.scatter.b, 1e-3)) * 0.6 * lightColor;
        water = mix(water, tint, G.compat.w);
    }
    // Seen from above, little is reflected, so a wake would barely show; light it like relief instead: slopes facing the sun
    // scatter more light up out of the water, slopes facing away less.
    if (!sheet && G.sunDir.w > 0.0) {
        vec3 sunW = normalize(mat3(G.invView) * G.sunDir.xyz);
        float facing = dot(normalize(vec3(-wk.x, 1.0, -wk.y)), sunW) - sunW.y;
        water *= 1.0 + clamp(facing * 2.5, -0.35, 0.35) * G.sunDir.w;
    }

    vec3 Rv = reflect(-V, n);
    vec3 Rw = (G.invView * vec4(Rv, 0.0)).xyz;
    float F = 0.02 + (max(1.0 - lostRough, 0.02) - 0.02) * pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 5.0);
    // The wake may add at most WAKE_FRESNEL to the reflection the water would have without it. Seen from above, a ring's face
    // tilted away from the camera meets the view at a grazing angle and is nearly a mirror: correct, but it drew opaque sky-blue
    // bands across water that otherwise shows its bed, which looked wrong from a few blocks up.
    if (wk.x != 0.0 || wk.y != 0.0) {
        vec3 nCalm = normalize(n + tilt);
        float calm = 0.02 + 0.98 * pow(1.0 - clamp(dot(nCalm, V), 0.0, 1.0), 5.0);
        F = min(F, calm + WAKE_FRESNEL);
    }
    // The reflection trace (up to 54 depth reads) is most of this pass. Seen from above, water reflects only 2-5% of the light,
    // so the traced scene could change the pixel by at most that fraction of (scene - sky): there it is skipped and the sky
    // stands in, fading in between SSR_MIN_F and twice that so no edge shows: traced when the view meets the surface less than
    // about 27 degrees below the horizon, skipped steeper than about 35 degrees.
    float hit = 0.0;
    vec3 ssr = vec3(0.0);
    float ssrWeight = smoothstep(SSR_MIN_F, 2.0 * SSR_MIN_F, F);
    if (ssrWeight > 0.0 && (pc.debugOff & 1) == 0) {
        float travelled;
        float level = dot(nGeo, upV);
        ssr = traceReflection(P + n * 0.05, Rv, P, abs(level) > 0.5 ? upV * sign(level) : vec3(0.0), hit, travelled);
        hit *= ssrWeight;
    }
    // Under cover (little sky light) the sky in the reflection dims toward the light there.
    float skyVisible = smoothstep(0.0, 0.7, bs.y);
    vec3 reflection = mix(distantReflection(P, Rv, Rw) * mix(lightColor * 0.3, vec3(1.0), skyVisible), ssr, hit);
    // Diagnostics (hfCompositeOff 32 / 64): only the refracted water, only the reflection.
    if ((pc.debugOff & 32) != 0) {
        return water;
    }
    if ((pc.debugOff & 64) != 0) {
        return reflection;
    }
    vec3 color = mix(water, reflection, F) + sunSpecular(n, V, footprint, lostRough) * smoothstep(0.5, 0.95, bs.y);
    // Wave crests are thinner, so light scattered in the water shows through them a little brighter.
    color += gScatter * lightColor * clamp(wk.z * 2.5, 0.0, 0.2);
    vec3 foamLit = vec3(0.9, 0.94, 0.96) * lightColor;
    if (wk.w > 0.01 && (pc.debugOff & 4) == 0) {
        float churn = foamTexture(world.xz * 1.4, wk.w * 0.85, footprint);
        color = mix(color, foamLit, churn * 0.75);
    }
    if (aer > 0.0 && (pc.debugOff & 4) == 0) {
        float cover;
        if (sheet) {
            // A sheet starts with exactly the top surface's foam and over its first half block turns into falling water's: foam
            // torn into streaks, patches stretched four times down the fall plus thin strands (two scales, wiggling a little)
            // whose density follows the aeration, so it whitens in strands with clearer water between them (preview/sheet2.py).
            float w = smoothstep(0.1, 0.45, sheetDrop);
            float top = w < 1.0 ? 0.7 * flowFoam(nearP, flow, aer * 0.85, footprint) : 0.0;
            float low = 0.0;
            if (w > 0.0) {
                float wig = (valueNoise(vec2(sheetAcross * 0.8, sheetDown * 0.4)) - 0.5) * 0.3;
                float fine = 1.0 - smoothstep(0.02, 0.08, footprint);
                float sv = valueNoise(vec2((sheetAcross + wig) * 7.0, sheetDown * 0.3)) * 0.6
                    + mix(0.5, valueNoise(vec2((sheetAcross + wig) * 16.1 + 3.1, sheetDown * 0.5)), fine) * 0.4;
                float thr = 1.0 - aer * 0.9;
                float strands = smoothstep(thr - 0.12, thr + 0.12, sv);
                low = 0.5 * foamTexture(vec2(sheetAcross, sheetDown * 0.25), aer, footprint) + 0.5 * strands;
            }
            cover = min(mix(top, low, w), 0.88);
        } else {
            cover = 0.7 * flowFoam(world.xz, flow, aer * 0.85, footprint);
        }
        color = mix(color, foamLit, clamp(cover, 0.0, 0.95));
    }
    return color;
}

float vanillaFog(vec3 viewPos) {
    vec3 rel = (G.invView * vec4(viewPos, 1.0)).xyz;
    float spherical = length(rel);
    float cylindrical = max(length(rel.xz), abs(rel.y));
    float env = clamp((spherical - G.fogB.x) / max(G.fogB.y - G.fogB.x, 1e-3), 0.0, 1.0);
    float far = clamp((cylindrical - G.fogB.z) / max(G.fogB.w - G.fogB.z, 1e-3), 0.0, 1.0);
    return max(env, far);
}

vec3 slimeRgb(int argb) {
    return vec3((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF) / 255.0;
}

vec3 slimeTexel(vec2 uv, float texelsPerBlock) {
    ivec2 size = ivec2(slimeTex[0], slimeTex[1]);
    ivec2 t = ivec2(floor(uv * texelsPerBlock));
    t = ((t % size) + size) % size;
    return slimeRgb(slimeTex[4 + t.x + t.y * size.x]);
}

vec3 slimeAlbedo(vec3 world, vec3 nWorld, float footprint) {
    float tpb = float(slimeTex[0]);
    vec3 w = pow(abs(nWorld), vec3(8.0));
    w /= w.x + w.y + w.z;
    vec3 c = w.y * slimeTexel(vec2(world.x, world.z), tpb)
           + w.x * slimeTexel(vec2(world.z, -world.y), tpb)
           + w.z * slimeTexel(vec2(world.x, -world.y), tpb);
    float detail = 1.0 - smoothstep(0.5, 1.5, footprint * tpb);
    return mix(slimeRgb(slimeTex[2]), c, detail);
}

vec3 shadeSlime(vec2 uv, vec3 n, float fd, vec2 bs) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = toEyeAt(uv);
    n = dot(n, V) < 0.0 ? -n : n;
    vec3 nWorld = normalize(mat3(G.invView) * n);
    vec3 world = worldAt(P) - nWorld * 0.01;
    vec3 albedo = slimeAlbedo(world, nWorld, pixelFootprint(fd));
    vec3 n2 = nWorld * nWorld;
    float face = n2.x * 0.6 + n2.z * 0.8 + n2.y * (nWorld.y > 0.0 ? 1.0 : 0.5);
    vec3 lit = min(albedo * face * vanillaLight(bs) * G.matD.y, vec3(1.0));
    float path = max(thicknessAt(uv), 0.0) * G.waterA.y;
    float opacity = mix(G.matD.w, 1.0, 1.0 - exp(-path * 3.0));
    vec3 behind = behindAt(uv) * mix(vec3(1.0), albedo * 1.3, 0.75);
    return mix(behind, lit, opacity);
}

// flow: heightfield water's velocity, so the ripples seen from below drift with the current too.
vec3 shadeSurfaceFromBelow(vec2 uv, vec3 n, float fd, vec3 deep, vec2 flow) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = toEyeAt(uv);
    n = dot(n, V) < 0.0 ? -n : n;
    vec3 nGeo = n;
    vec3 wrinkled = rippleNormal(n, P, pixelFootprint(fd), flow);
    n = normalize(mix(n, wrinkled, smoothstep(0.0, 0.15, dot(wrinkled, V))));
    float cosI = clamp(dot(n, V), 0.0, 1.0);
    const float ETA = 1.333;
    float sinT2 = ETA * ETA * (1.0 - cosI * cosI);
    vec2 ruv = clamp(uv - n.xy * G.waterA.z * 0.6, vec2(0.0), vec2(1.0));
    vec3 above = behindAt(ruv);
    if (G.compat.y < 0.5) {
        // Underwater mirror off (the default): no total internal reflection, the surface stays see-through at every angle.
        float F = (0.02 + 0.98 * pow(1.0 - cosI, 5.0)) * 0.25;
        return mix(above, deep * 1.4, F);
    }
    // Underwater mirror on: seen from below, the surface reflects the water under it: totally outside Snell's window (beyond ~48
    // degrees), partly inside it. It mirrors the submerged scene (screen-space trace, fogged over the reflected path), and where
    // the trace finds nothing, the water's own colour in the distance (the fog colour), so it blends into the fog like the scene
    // does; a flat colour there was an opaque slab that cut across everything standing out of the water.
    float F = 1.0;
    if (sinT2 < 1.0) {
        float cosT = sqrt(1.0 - sinT2);
        F = 0.02 + 0.98 * pow(1.0 - cosT, 5.0);
    }
    vec3 reflected = deep;
    if ((pc.debugOff & 1) == 0 && F > 0.05) {
        vec3 Rv = reflect(-V, n);
        float hit;
        float travelled;
        vec3 upV = normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
        float level = dot(nGeo, upV);
        vec3 ssr = traceReflection(P + n * 0.05, Rv, P, abs(level) > 0.5 ? upV * sign(level) : vec3(0.0), hit, travelled);
        float fog = G.scatter.w * G.misc.x;
        ssr = ssr * exp(-gAbsorb * travelled * G.misc.x) + deep * (1.0 - exp(-fog * travelled));
        reflected = mix(deep, ssr, hit);
    }
    if (sinT2 >= 1.0 || (pc.debugOff & 64) != 0) {
        return (pc.debugOff & 32) != 0 ? vec3(0.0) : reflected;
    }
    if ((pc.debugOff & 32) != 0) {
        return above;
    }
    return mix(above, reflected, F);
}

vec3 shadePixel(vec2 uv) {
    vec4 g = texture(gbuffer, uv);
    float fd = g.w;
    vec4 matSample = texture(materialTex, uv);
    float matId = matSample.r;
    vec2 surfaceLight = matSample.gb;
    bool lavaPass = abs(pc.filterMat - 2.0) < 0.5;
    bool slimePass = pc.filterMat > 2.5;
    bool surfaceOnly = lavaPass || slimePass;
    vec4 foam = slimePass || !readable(uv) ? vec4(0.0) : texture(foamTex, uv);
    float sceneDev = texture(sceneDepth, uv).r;
    float sceneDist = linearDepth(uv, sceneDev);
    bool hasFluid = fd > 0.0 && fd < sceneDist;
    bool underwater = viewState.x > 0.5 && !slimePass;
    bool matTagged = matId >= -0.5;
    if (hasFluid && matTagged) {
        if (abs(matId - pc.filterMat) > 0.25) {
            discard;
        }
    } else if (surfaceOnly && !(slimePass && G.matD.x > 0.5)) {
        discard;
    }
    if (!surfaceOnly) {
        bool seaPixel = matSample.a > 1.5;
        if (hasFluid && matTagged ? seaPixel != (pc.sea > 0.5) : pc.background < 0.5) {
            discard;
        }
    }
    if (slimePass) {
        vec3 slime = behindAt(uv);
        float slimeDepth = sceneDev;
        if (hasFluid && matTagged) {
            slime = shadeSlime(uv, g.xyz, fd, surfaceLight) * (1.0 - matSample.a);
            vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
            slimeDepth = clip.z / clip.w;
        }
        if (G.matD.x > 0.5) {
            vec3 inside = slimeRgb(slimeTex[2]) * 0.55 * clamp(G.skyColor.w / 0.85, 0.2, 1.0) * G.matD.y;
            slime = mix(slime, inside, G.matD.z);
        }
        gDepth = slimeDepth;
        return slime;
    }
    bool vanillaLavaFog = lavaPass && G.killInfo.w > 0.0;
    if (vanillaLavaFog) {
        if (!hasFluid) {
            discard;
        }
        vec3 lavaColor = underwater ? G.scatter.rgb * (1.2 + G.skyColor.w) * 1.35 : shadeAboveLava(uv, g.xyz, fd, G.skyColor.w);
        float dist = length(viewPosAt(uv, fd));
        float f = clamp((dist - G.killInfo.z) / max(G.killInfo.w - G.killInfo.z, 1e-3), 0.0, 1.0);
        vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
        gDepth = clip.z / clip.w;
        return mix(lavaColor, G.fogColor.rgb, f);
    }
    if (!hasFluid && foam.a < 1e-3 && !underwater) {
        discard;
    }
    float ambient = G.skyColor.w;
    vec3 color = behindAt(uv);
    float outDepth = sceneDev;
    bool seaSurface = hasFluid && matTagged && matSample.a > 1.5;
    gSeaRough = seaSurface ? clamp(fract(matSample.a), 0.0, 0.9) : 0.0;
    float whitecap = seaSurface ? floor(matSample.a - 2.0 + 0.01) / 7.0 : 0.0;
    seaLook(underwater || !hasFluid ? vec3(0.0) : (G.invView * vec4(viewPosAt(uv, fd), 1.0)).xyz);

    // Heightfield water packs aeration, the sheet flag and its velocity into the material value (see mesh_front.frag).
    bool hfWater = matTagged && matId >= 0.125 && matId < 0.25;
    bool sheet = false;
    float aer = 0.0;
    vec2 flow = vec2(0.0);
    float sheetDrop = 0.0;
    if (hasFluid && hfWater) {
        uint code = floatBitsToUint(matId) & 0x7FFFFFu;
        aer = float(code & 63u) / 63.0;
        sheet = (code & 64u) != 0u;
        if (sheet) {
            flow = SHEET_OUTWARD[(code >> 7) & 3u] * (float((code >> 9) & 63u) / 63.0 * 8.0);
            sheetDrop = float((code >> 15) & 255u) / 255.0 * SHEET_NEAR;
        } else {
            float angle = float((code >> 7) & 255u) / 255.0 * 6.2831853;
            flow = vec2(cos(angle), sin(angle)) * (float((code >> 15) & 255u) / 255.0 * 8.0);
        }
    }

    if (underwater) {
        vec3 deep = lavaPass ? G.scatter.rgb * (1.2 + ambient) : gScatter * ambient;
        float visible = min(sceneDist, 160.0);
        if (hasFluid) {
            color = lavaPass ? deep * 1.35 : shadeSurfaceFromBelow(uv, g.xyz, fd, deep, sheet ? vec2(0.0) : flow);
            visible = fd;
            vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
            outDepth = clip.z / clip.w;
        } else if (viewState.w > 0.5) {
            // Under heightfield water with no surface drawn in front: a ray going up leaves the water at the surface height
            // (hf_probe.comp, view state z). With the eye a few cm under it the surface is nearer than the near plane and not drawn
            // at all, and the whole view above the waterline was fogged as if the water went on to the scene.
            vec3 p0 = viewPosAt(uv, 1.0);
            vec3 dirV = normalize(viewPosAt(uv, 2.0) - p0);
            float up = (mat3(G.invView) * dirV).y;
            if (up > 1.0e-4) {
                float toSurface = viewState.z / up * -dirV.z;
                visible = min(visible, max(toSurface, 0.0));
            }
        }
        float fog = G.scatter.w * G.misc.x;
        color = color * exp(-gAbsorb * visible * G.misc.x) + deep * (1.0 - exp(-fog * visible));
    } else if (hasFluid) {
        color = lavaPass ? shadeAboveLava(uv, g.xyz, fd, ambient)
                         : shadeAboveWater(uv, g.xyz, fd, sceneDist, ambient, surfaceLight, aer, sheet, flow, sheetDrop);
        vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
        outDepth = clip.z / clip.w;
    }

    if (whitecap > 0.0 && !underwater) {
        vec3 P = viewPosAt(uv, fd);
        vec3 world = worldAt(P);
        float lace = foamLace(world.xz * 0.8, whitecap, pixelFootprint(fd) * 0.8);
        float cover = whitecap * mix(0.55, 1.0, lace);
        vec3 L = G.sunDir.w > 0.0 ? normalize(G.sunDir.xyz) : normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
        float lit = mix(0.72, 1.08, clamp(dot(normalize(g.xyz), L) * 0.5 + 0.5, 0.0, 1.0));
        color = mix(color, vec3(0.93, 0.96, 1.0) * lit * vanillaLight(surfaceLight), clamp(cover, 0.0, 0.95));
    }

    if (foam.a > 1e-4) {
        vec3 foamColor = foam.rgb / foam.a;
        float coverage = min(1.0 - exp(-foam.a * G.foamA.y), 0.9);
        vec4 foamSurface = texture(foamSurfaceTex, uv);
        float surface = clamp(foamSurface.r / foam.a, 0.0, 1.0);
        // The particle regions' surface foam is laced; over heightfield water the whitewater is only spray and splash drops
        // flying in front of it (its surface foam is shaded in shadeAboveWater), which the lace cut holes into.
        if (hasFluid && !underwater) {
            if (!hfWater) {
                vec3 world = worldAt(viewPosAt(uv, fd));
                coverage *= mix(1.0, foamLace(world.xz, coverage, pixelFootprint(fd)), surface);
            }
        } else if (!underwater) {
            float foamDist = foamSurface.g / foam.a;
            if (foamDist > 0.0) {
                vec3 P = viewPosAt(uv, foamDist);
                vec3 world = worldAt(P);
                coverage *= mix(1.0, foamLace(world.xz, coverage, pixelFootprint(foamDist)), surface);
                if (!hasFluid && G.compat.x < 0.5 && coverage > 0.35) {
                    vec4 clip = G.proj * vec4(P, 1.0);
                    outDepth = max(outDepth, clip.z / clip.w);
                }
            }
        }
        vec3 foamLight = vanillaLight(hasFluid && matTagged ? surfaceLight : vec2(0.0, 1.0));
        color = mix(color, foamColor * foamLight, coverage);
    }

    if (hasFluid && !underwater && matSample.a > 1.5) {
        color = mix(color, G.fogColor.rgb, vanillaFog(viewPosAt(uv, fd)));
    }

    gDepth = outDepth;
    return color;
}

bool debugView(vec2 uv) {
    if (pc.debug < 0.5 || abs(pc.filterMat) > 0.25) {
        return false;
    }
    if (pc.sea < 0.5) {
        discard;
    }
    float fd = texture(gbuffer, uv).w;
    if (fd <= 0.0) {
        discard;
    }
    vec4 mat = texture(materialTex, uv);
    float sceneDist = linearDepth(uv, texture(sceneDepth, uv).r);
    vec3 c = fd >= sceneDist ? vec3(1.0, 1.0, 0.0) : mat.r < -0.5 ? vec3(1.0, 0.0, 1.0) : mat.a > 1.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.3, 1.0);
    outColor = vec4(c, 1.0);
    gl_FragDepth = texture(sceneDepth, uv).r;
    return true;
}

void main() {
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
    if (debugView(uv)) {
        return;
    }
    if (G.compat.x < 0.5) {
        outColor = vec4(shadePixel(uv), 1.0);
        gl_FragDepth = gDepth;
        return;
    }
    gBackground = 0.0;
    vec3 over = shadePixel(uv);
    gBackground = 1.0;
    vec3 through = clamp(shadePixel(uv) - over, vec3(0.0), vec3(1.0));
    outColor = vec4(max(over, vec3(0.0)), 1.0 - dot(through, vec3(1.0 / 3.0)));
    gl_FragDepth = gDepth;
}
