#version 450
#include "common.glsl"
layout(binding = 1) uniform sampler2D gbuffer;
layout(binding = 2) uniform sampler2D thicknessTex;
layout(binding = 3) uniform sampler2D sceneColor;
layout(binding = 4) uniform sampler2D sceneDepth;
layout(binding = 5) uniform sampler2D foamTex;
layout(binding = 6) uniform sampler2D materialTex;
layout(std430, binding = 7) readonly buffer ViewState { vec4 viewState; };
// Material filter, then the player's wake (HfRipples): the drawn water level it lies on, normal gain, number of entries
// (0 = off), wave life (s) and foam strength.
// The wake only exists within wakeRadius of wakeCenter (wrapped world xz); pixels farther away skip it.
layout(push_constant) uniform Push {
    float filterMat;
    float wakeY;
    float wakeGain;
    int wakeCount;
    float wakeLife;
    float wakeFoam;
    vec2 wakeCenter;
    float wakeRadius;
    // WaterSettings.hfCompositeOff, for measuring what each part costs: 1 no reflection trace, 2 no ripples, 4 no foam, 8 no wake.
    int debugOff;
} pc;
// Points where the player disturbed the surface, two vec4 each: ((x, z) in wrapped world blocks, seconds since, displaced volume
// in blocks^3), (foam it churned up, 0, 0, 0).
layout(std430, binding = 8) readonly buffer Wake { vec4 wake[]; };
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265;

// View-space distance of the scene at screen point uv. The level projection includes view bobbing (a small rotation and
// shift), so depth must be unprojected at the pixel itself: reading it at the screen centre was off by a few percent toward
// the screen edges, swinging with every bob, which made thickness, refraction and reflections jump while walking.
float linearDepth(vec2 uv, float device) {
    vec4 v = G.invProj * vec4(uv * 2.0 - 1.0, device, 1.0);
    return -v.z / v.w;
}

// Point at view-space distance dist along the camera ray through screen point uv. The level projection includes view bobbing,
// which shifts the camera a little, so the ray does not pass through the view-space origin: it is rebuilt from two points
// unprojected at different depths. (Scaling a single near-plane point, as before, assumed the origin and was off by the bob
// shift magnified ~10x, so every water pixel's position, view direction and ripple coordinates swung with each step.)
vec3 viewPosAt(vec2 uv, float dist) {
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 a = G.invProj * vec4(ndc, 0.2, 1.0);
    vec4 b = G.invProj * vec4(ndc, 0.8, 1.0);
    vec3 p0 = a.xyz / a.w;
    vec3 p1 = b.xyz / b.w;
    vec3 ray = p1 - p0;
    return p0 + ray * ((-dist - p0.z) / ray.z);
}

// Unit vector from the water point at uv back toward the (bobbed) eye, exact even when the eye is not at the origin. The depth
// buffer is reversed (near = 1), so which unprojected point is nearer is decided by distance, not by depth value.
vec3 toEyeAt(vec2 uv) {
    vec2 ndc = uv * 2.0 - 1.0;
    vec4 a = G.invProj * vec4(ndc, 0.2, 1.0);
    vec4 b = G.invProj * vec4(ndc, 0.8, 1.0);
    vec3 pa = a.xyz / a.w;
    vec3 pb = b.xyz / b.w;
    return -pa.z < -pb.z ? normalize(pa - pb) : normalize(pb - pa);
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

// Screen-space reflection. The coarse march finds the first step behind the scene, then a binary search pins the crossing
// down: without it a ray that overshoots by up to a step "hits" whatever lies there, which drew jagged teeth of the cliff
// into the water next to it and made reflections jump with every small camera move. A crossing far behind the surface
// (the ray passed behind an object) is no hit; rays heading back toward the camera and long rays fade out.
vec3 traceReflection(vec3 origin, vec3 R, out float hit) {
    hit = 0.0;
    vec3 pos = origin;
    vec3 last = origin;
    float stepLen = 0.15;
    vec2 suv;
    for (int i = 0; i < 48; i++) {
        last = pos;
        pos += R * stepLen;
        if (!projectToScreen(pos, suv)) {
            break;
        }
        float sceneDist = linearDepth(suv, texture(sceneDepth, suv).r);
        if (-pos.z > sceneDist) {
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
                break;
            }
            vec2 edge = smoothstep(vec2(0.0), vec2(0.08), suv) * (1.0 - smoothstep(vec2(0.92), vec2(1.0), suv));
            float travel = 1.0 - smoothstep(0.6, 1.0, float(i) / 48.0);
            float away = 1.0 - smoothstep(0.1, 0.5, R.z);
            hit = edge.x * edge.y * travel * away;
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

// Small wind ripples: 12 directional waves with scattered directions, phases and deep-water speeds, their strength varied over
// the surface by slow noise and their crests bent by a static warp, so the pattern does not repeat (5 fixed waves read as
// regular stripes). Short waves spread wider around the wind direction, like real wind ripples.
vec2 rippleGradient(vec2 p, float t, float footprint) {
    vec2 g = vec2(0.0);
    vec2 q = p + 0.6 * vec2(valueNoise(p * 0.21), valueNoise(p * 0.21 + 5.2));
    float patchA = 0.3 + 1.4 * valueNoise(p * 0.03 + vec2(t * 0.01, 3.7));
    float patchB = 0.3 + 1.4 * valueNoise(p * 0.07 + vec2(9.1, t * 0.015));
    float freq = 0.9;
    for (int i = 0; i < 12; i++) {
        float lambda = 6.2831853 / freq;
        float fade = smoothstep(4.0, 12.0, lambda / max(footprint, 1e-5));
        if (fade <= 0.0) {
            break;
        }
        // Directions step by the golden angle within a spread that widens for shorter waves, so no two neighbors line up.
        float h2 = fract(sin(float(i) * 78.233 + 1.7) * 43758.5453);
        float ang = 0.5 + (fract(float(i) * 0.618034 + 0.31) - 0.5) * 2.0 * (0.9 + 0.8 * float(i) / 11.0);
        vec2 dir = vec2(cos(ang), sin(ang));
        float w = 0.1015 * pow(freq, -0.15) * ((i & 1) == 0 ? patchA : patchB);
        float phase = dot(q, dir) * freq + t * sqrt(freq) * 1.1 + h2 * 6.2831853;
        g += dir * (w * fade * cos(phase));
        freq *= 1.31;
    }
    return g;
}

// Flow mapping: two copies of a pattern carried along by the water, each restarted every cycle, half a cycle apart, and blended
// by a triangle weight that is zero when a copy restarts, so the pattern moves with the current without ever visibly jumping.
const float FLOW_CYCLE = 1.6;

void flowPhases(vec2 flow, out vec2 off0, out vec2 off1, out float w0) {
    float t = G.simB.z / FLOW_CYCLE;
    float p0 = fract(t);
    float p1 = fract(t + 0.5);
    off0 = flow * (p0 - 0.5) * FLOW_CYCLE;
    off1 = flow * (p1 - 0.5) * FLOW_CYCLE;
    w0 = 1.0 - abs(2.0 * p0 - 1.0);
}

// flow: water velocity (blocks/s, world x/z) for heightfield water, so the ripples drift with rivers and streams.
vec3 rippleNormal(vec3 nView, vec3 viewPos, float footprint, vec2 flow) {
    if (G.shadeA.x <= 0.0) {
        return nView;
    }
    vec3 nWorld = normalize(mat3(G.invView) * nView);
    vec3 world = G.misc.yzw + (G.invView * vec4(viewPos, 0.0)).xyz;
    float scale = max(G.shadeA.y, 1e-3);
    // At grazing angles a pixel covers far more surface along the view direction than its distance alone implies;
    // without this the finer ripple bands alias into moire rings on large flat water.
    float grazing = max(abs(dot(nView, normalize(-viewPos))), 0.05);
    float t = G.simB.z * G.shadeA.z;
    float fp = footprint * scale / grazing;
    vec2 grad;
    if (dot(flow, flow) > 0.0025) {
        vec2 off0;
        vec2 off1;
        float w0;
        flowPhases(flow, off0, off1, w0);
        grad = rippleGradient((world.xz - off0) * scale, t, fp) * w0
            + rippleGradient((world.xz - off1) * scale + vec2(37.1, 11.3), t, fp) * (1.0 - w0);
        // Faster water is rougher.
        grad *= 1.0 + 0.12 * min(length(flow), 6.0);
    } else {
        grad = rippleGradient(world.xz * scale, t, fp);
    }
    vec3 g = vec3(grad.x, 0.0, grad.y) * G.shadeA.x;
    return normalize(mat3(G.view) * normalize(nWorld - (g - nWorld * dot(g, nWorld))));
}

vec3 rippleNormal(vec3 nView, vec3 viewPos, float footprint) {
    return rippleNormal(nView, viewPos, footprint, vec2(0.0));
}

vec3 sunSpecular(vec3 n, vec3 V, float footprint) {
    vec3 L = normalize(G.sunDir.xyz);
    float NoL = dot(n, L);
    if (NoL <= 0.0 || G.sunDir.w <= 0.0) {
        return vec3(0.0);
    }
    vec3 H = normalize(L + V);
    float NoH = max(dot(n, H), 0.0);
    float NoV = max(dot(n, V), 1e-3);
    float rough = clamp(G.shadeA.w + footprint * 0.25, 0.02, 0.7);
    float a2 = rough * rough * rough * rough;
    float den = NoH * NoH * (a2 - 1.0) + 1.0;
    float D = a2 / max(PI * den * den, 1e-9);
    float F = 0.02 + 0.98 * pow(1.0 - max(dot(V, H), 0.0), 5.0);
    float k = rough * rough * 0.5;
    float Vis = 0.5 / max(NoL * (NoV * (1.0 - k) + k) + NoV * (NoL * (1.0 - k) + k), 1e-5);
    return vec3(1.0, 0.96, 0.88) * min(D * Vis * F * NoL * G.shadeB.x, 30.0) * G.sunDir.w;
}

vec3 shadeAboveLava(vec2 uv, vec3 n, float fd, float ambient) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = toEyeAt(uv);
    n = dot(n, V) < 0.0 ? -n : n;
    float footprint = pixelFootprint(fd);
    vec3 wrinkled = rippleNormal(n, P, footprint);
    n = normalize(mix(n, wrinkled, smoothstep(0.0, 0.2, dot(wrinkled, V))));
    float path = max(texture(thicknessTex, uv).r, 0.0) * G.waterA.y;
    vec3 glow = G.scatter.rgb * (1.4 + ambient);
    vec3 core = glow * (1.0 - exp(-G.scatter.w * path * 0.45));
    float fresnel = pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 2.0);
    vec3 rim = glow * fresnel * 0.85;
    return core + rim + sunSpecular(n, V, footprint) * vec3(1.0, 0.65, 0.25);
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
    if (speed < 0.05) {
        return foamTexture(p, coverage, footprint);
    }
    vec2 off0;
    vec2 off1;
    float w0;
    flowPhases(flow, off0, off1, w0);
    vec2 smear = flow * (0.12 * min(speed, 6.0) / speed);
    vec2 a = p - off0;
    vec2 b = p - off1 + vec2(13.7, 5.1);
    float fa = foamShape(foamField(a, smear, footprint), coverage, footprint);
    float fb = foamShape(foamField(b, smear, footprint), coverage, footprint);
    return mix(fb, fa, w0);
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

vec3 shadeAboveWater(vec2 uv, vec3 n, float fd, float sceneDist, float ambient, float aer, bool sheet, vec2 flow, float sheetDrop) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = toEyeAt(uv);
    n = dot(n, V) < 0.0 ? -n : n;
    float footprint = pixelFootprint(fd);
    vec3 nWorld = normalize(mat3(G.invView) * n);
    vec3 world = G.misc.yzw + (G.invView * vec4(P, 0.0)).xyz;
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
        float fallSpeed = max(length(flow), 1.0);
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
    float thickness = max(texture(thicknessTex, uv).r, 0.0);
    vec3 upV = normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
    float vertical = thickness * abs(dot(V, upV));
    // Thin films lie flat; a falling sheet is thin too but must keep its own normal.
    float film = sheet ? 0.0 : 1.0 - smoothstep(0.02, max(G.shadeB.z, 0.03), vertical);
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
    if ((pc.debugOff & 2) == 0) {
        vec3 wrinkled = rippleNormal(n, P, footprint, flow);
        n = normalize(mix(n, wrinkled, lip * smoothstep(0.0, 0.15, dot(wrinkled, V))));
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
    float path = max(texture(thicknessTex, ruv).r, 0.0);
    if (refrDist < fd) {
        ruv = uv;
        path = thickness;
    }
    path *= G.waterA.y;
    vec3 under = texture(sceneColor, ruv).rgb;
    // Ground under water looks wet: darker and more saturated. Fully from 1.5 cm of water in the view (it was 3 cm, and only
    // pow 1.3 x 0.955 at the default 0.3, so a film running down terraces or a sheet down a block face left the ground behind
    // looking dry; wet soil reflects about half the light dry soil does).
    // Judged by this pixel's own water, not by the refracted path: the ripples move the refraction offset every frame, and at the
    // edge of a thin film (thickness falling to 0 within a few pixels) the darkening flickered between wet and dry.
    float wet = G.shadeB.y * smoothstep(0.0, 0.015, max(thickness * G.waterA.y, path));
    under = pow(max(under, vec3(0.0)), vec3(1.0 + 1.5 * wet)) * (1.0 - 0.5 * wet);
    vec3 transmitted = under * exp(-G.absorb.rgb * path);
    vec3 scattered = G.scatter.rgb * ambient * (1.0 - exp(-G.scatter.w * path));
    vec3 water = transmitted + scattered;
    // Seen from above, little is reflected, so a wake would barely show; light it like relief instead: slopes facing the sun
    // scatter more light up out of the water, slopes facing away less.
    if (!sheet && G.sunDir.w > 0.0) {
        vec3 sunW = normalize(mat3(G.invView) * G.sunDir.xyz);
        float facing = dot(normalize(vec3(-wk.x, 1.0, -wk.y)), sunW) - sunW.y;
        water *= 1.0 + clamp(facing * 2.5, -0.35, 0.35) * G.sunDir.w;
    }

    vec3 Rv = reflect(-V, n);
    vec3 Rw = (G.invView * vec4(Rv, 0.0)).xyz;
    float F = 0.02 + 0.98 * pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 5.0);
    // The reflection trace (up to 54 depth reads) is most of this pass. Seen from above, water reflects only 2-5% of the light,
    // so the traced scene could change the pixel by at most that fraction of (scene - sky): there it is skipped and the sky
    // stands in, fading in between SSR_MIN_F and twice that so no edge shows: traced when the view meets the surface less than
    // about 27 degrees below the horizon, skipped steeper than about 35 degrees.
    float hit = 0.0;
    vec3 ssr = vec3(0.0);
    float ssrWeight = smoothstep(SSR_MIN_F, 2.0 * SSR_MIN_F, F);
    if (ssrWeight > 0.0 && (pc.debugOff & 1) == 0) {
        ssr = traceReflection(P + n * 0.05, Rv, hit);
        hit *= ssrWeight;
    }
    vec3 reflection = mix(skyColor(Rw), ssr, hit);
    vec3 color = mix(water, reflection, F) + sunSpecular(n, V, footprint);
    // Wave crests are thinner, so light scattered in the water shows through them a little brighter.
    color += G.scatter.rgb * ambient * clamp(wk.z * 2.5, 0.0, 0.2);
    if (wk.w > 0.01 && (pc.debugOff & 4) == 0) {
        float churn = foamTexture(world.xz * 1.4, wk.w * 0.85, footprint);
        color = mix(color, vec3(0.9, 0.94, 0.96) * mix(0.25, 1.0, ambient), churn * 0.75);
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
        color = mix(color, vec3(0.9, 0.94, 0.96) * mix(0.25, 1.0, ambient), clamp(cover, 0.0, 0.95));
    }
    return color;
}


vec3 shadeSurfaceFromBelow(vec2 uv, vec3 n, float fd, vec3 deep) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = toEyeAt(uv);
    n = dot(n, V) < 0.0 ? -n : n;
    vec3 wrinkled = rippleNormal(n, P, pixelFootprint(fd));
    n = normalize(mix(n, wrinkled, smoothstep(0.0, 0.15, dot(wrinkled, V))));
    float cosI = clamp(dot(n, V), 0.0, 1.0);
    const float ETA = 1.333;
    float sinT2 = ETA * ETA * (1.0 - cosI * cosI);
    if (sinT2 >= 1.0) {
        return deep * 1.4;
    }
    float cosT = sqrt(1.0 - sinT2);
    float F = 0.02 + 0.98 * pow(1.0 - cosT, 5.0);
    vec2 ruv = clamp(uv - n.xy * G.waterA.z * 0.6, vec2(0.0), vec2(1.0));
    vec3 above = texture(sceneColor, ruv).rgb;
    return mix(above, deep * 1.4, F);
}

void main() {
    vec2 uv = gl_FragCoord.xy * G.screen.zw;
    vec4 g = texture(gbuffer, uv);
    float fd = g.w;
    float matId = texture(materialTex, uv).r;
    bool lavaPass = pc.filterMat > 1.5;
    vec4 foam = texture(foamTex, uv);
    float sceneDev = texture(sceneDepth, uv).r;
    float sceneDist = linearDepth(uv, sceneDev);
    bool hasFluid = fd > 0.0 && fd < sceneDist;
    bool underwater = viewState.x > 0.5;
    bool matTagged = matId >= -0.5;
    if (hasFluid && matTagged) {
        if (lavaPass) {
            if (abs(matId - 2.0) > 0.25) {
                discard;
            }
        } else if (abs(matId) > 0.25) {
            discard;
        }
    } else if (hasFluid && !matTagged) {
        if (lavaPass) {
            discard;
        }
    } else if (lavaPass) {
        discard;
    }
    if (!hasFluid && foam.a < 1e-3 && !underwater) {
        discard;
    }
    float ambient = G.skyColor.w;
    vec3 color = texture(sceneColor, uv).rgb;
    float outDepth = sceneDev;

    if (underwater) {
        vec3 deep = lavaPass ? G.scatter.rgb * (1.2 + ambient) : G.scatter.rgb * ambient;
        float visible = min(sceneDist, 160.0);
        if (hasFluid) {
            color = lavaPass ? deep * 1.35 : shadeSurfaceFromBelow(uv, g.xyz, fd, deep);
            visible = fd;
            vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
            outDepth = clip.z / clip.w;
        }
        float fog = G.scatter.w * G.misc.x;
        color = color * exp(-G.absorb.rgb * visible * G.misc.x) + deep * (1.0 - exp(-fog * visible));
    } else if (hasFluid) {
        // Heightfield water packs aeration, the sheet flag and its velocity into the material value (see mesh_front.frag).
        bool sheet = false;
        float aer = 0.0;
        vec2 flow = vec2(0.0);
        float sheetDrop = 0.0;
        if (matTagged && matId >= 0.125 && matId < 0.25) {
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
        color = lavaPass ? shadeAboveLava(uv, g.xyz, fd, ambient)
                         : shadeAboveWater(uv, g.xyz, fd, sceneDist, ambient, aer, sheet, flow, sheetDrop);
        vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
        outDepth = clip.z / clip.w;
    }

    if (foam.a > 1e-4) {
        vec3 foamColor = foam.rgb / foam.a;
        float coverage = min(1.0 - exp(-foam.a * G.foamA.y), 0.9);
        // The particle regions' surface foam is laced; over heightfield water the whitewater is only spray and splash drops
        // flying in front of it (its surface foam is shaded in shadeAboveWater), which the lace cut holes into.
        bool hfWater = matTagged && matId >= 0.125 && matId < 0.25;
        if (hasFluid && !underwater && !hfWater) {
            vec3 world = G.misc.yzw + (G.invView * vec4(viewPosAt(uv, fd), 0.0)).xyz;
            coverage *= foamLace(world.xz, coverage, pixelFootprint(fd));
        }
        color = mix(color, foamColor * mix(0.25, 1.0, ambient), coverage);
    }

    outColor = vec4(color, 1.0);
    gl_FragDepth = outDepth;
}
