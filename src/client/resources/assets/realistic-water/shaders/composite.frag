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

vec3 rippleNormal(vec3 nView, vec3 viewPos, float footprint) {
    if (G.shadeA.x <= 0.0) {
        return nView;
    }
    vec3 nWorld = normalize(mat3(G.invView) * nView);
    vec3 world = G.misc.yzw + (G.invView * vec4(viewPos, 0.0)).xyz;
    float scale = max(G.shadeA.y, 1e-3);
    // At grazing angles a pixel covers far more surface along the view direction than its distance alone implies;
    // without this the finer ripple bands alias into moire rings on large flat water.
    float grazing = max(abs(dot(nView, normalize(-viewPos))), 0.05);
    vec2 grad = rippleGradient(world.xz * scale, G.simB.z * G.shadeA.z, footprint * scale / grazing);
    vec3 g = vec3(grad.x, 0.0, grad.y) * G.shadeA.x;
    return normalize(mat3(G.view) * normalize(nWorld - (g - nWorld * dot(g, nWorld))));
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


// Soft drifting foam patches; coverage grows with aer (more and larger patches), edges soften with distance.
// Foam: crisp-edged patches whose coverage grows with the foam amount, made of fine round bubbles (the nearest point of a
// jittered grid), drifting slowly. Detail fades out where a pixel would cover it, so it never aliases or turns to mush.
float foamTexture(vec2 p, float coverage, float footprint) {
    float t = G.simB.z;
    vec2 drift = vec2(t * 0.07, -t * 0.05);
    float d1 = 1.0 - smoothstep(0.03, 0.12, footprint);
    float d2 = 1.0 - smoothstep(0.012, 0.05, footprint);
    float v = valueNoise(p * 1.1 + drift) * 0.45 + valueNoise(p * 2.9 + vec2(3.1, 0.0) - drift) * 0.3
        + mix(0.5, valueNoise(p * 9.0 + vec2(0.0, 7.7) + drift * 2.0), d1) * 0.17
        + mix(0.5, valueNoise(p * 23.0 + vec2(1.3, 0.0) - drift * 3.0), d2) * 0.08;
    float edge = 0.025 + 1.5 * footprint;
    float cover = smoothstep(1.0 - coverage - edge, 1.0 - coverage + edge, v);
    if (cover <= 0.0 || d2 <= 0.0) {
        return cover;
    }
    vec2 q = (p + drift) * 12.0;
    vec2 c = floor(q);
    float best = 9.0;
    float radius = 0.0;
    for (int k = 0; k < 9; k++) {
        vec2 cell = c + vec2(float(k % 3 - 1), float(k / 3 - 1));
        vec2 feature = cell + 0.15 + 0.7 * vec2(noiseHash(cell), noiseHash(cell + vec2(17.0, 31.0)));
        float d = distance(q, feature);
        if (d < best) {
            best = d;
            float hr = noiseHash(cell + vec2(5.0, 11.0));
            radius = hr < 0.4 ? 0.0 : 0.12 + 0.3 * (hr - 0.4);
        }
    }
    float pore = radius > 0.0 ? 1.0 - smoothstep(radius - 0.06, radius + 0.06, best) : 0.0;
    return cover * (1.0 - 0.4 * d2 * pore);
}

// Aerated water (heightfield only, aer from the material id): falling sheets get streaks running down them and a bumpy normal
// instead of the flat glass look; foam on the surface shows as soft patches. Ripples around the player bend the surface normal.
vec3 shadeAboveWater(vec2 uv, vec3 n, float fd, float sceneDist, float ambient, float aer, bool sheet) {
    vec3 P = viewPosAt(uv, fd);
    vec3 V = toEyeAt(uv);
    n = dot(n, V) < 0.0 ? -n : n;
    float footprint = pixelFootprint(fd);
    vec3 nWorld = normalize(mat3(G.invView) * n);
    vec3 world = G.misc.yzw + (G.invView * vec4(P, 0.0)).xyz;
    float streaks = 0.5;
    if (sheet) {
        vec2 along = normalize(vec2(-nWorld.z, nWorld.x) + vec2(1.0e-4, 0.0));
        float u = dot(world.xz, along);
        float t = G.simB.z;
        float detail = smoothstep(1.0, 3.0, 0.14 / max(footprint, 1.0e-5));
        float s1 = valueNoise(vec2(u * 7.0, (world.y + t * 3.5) * 0.7));
        float s2 = valueNoise(vec2(u * 15.0 + 3.1, (world.y + t * 4.5) * 1.3));
        streaks = mix(0.5, s1 * 0.65 + s2 * 0.35, detail);
        vec3 side = mat3(G.view) * vec3(along.x, 0.0, along.y);
        n = normalize(n + side * ((s1 - 0.5) * 0.8 * detail));
    }
    float thickness = max(texture(thicknessTex, uv).r, 0.0);
    vec3 upV = normalize(mat3(G.view) * vec3(0.0, 1.0, 0.0));
    float vertical = thickness * abs(dot(V, upV));
    // Thin films lie flat; a falling sheet is thin too but must keep its own normal.
    float film = sheet ? 0.0 : 1.0 - smoothstep(0.02, max(G.shadeB.z, 0.03), vertical);
    n = normalize(mix(n, upV, film * 0.9));
    vec3 tilt = vec3(0.0);
    vec4 wk = vec4(0.0);
    if (!sheet) {
        wk = wakeAt(world, footprint);
        vec2 slope = wk.xy * pc.wakeGain;
        tilt = mat3(G.view) * vec3(slope.x, 0.0, slope.y);
        n = normalize(n - tilt);
    }
    vec3 wrinkled = rippleNormal(n, P, footprint);
    n = normalize(mix(n, wrinkled, smoothstep(0.0, 0.15, dot(wrinkled, V))));

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
    float wet = G.shadeB.y * smoothstep(0.0, 0.03, path);
    under = pow(max(under, vec3(0.0)), vec3(1.0 + wet)) * (1.0 - 0.15 * wet);
    vec3 transmitted = under * exp(-G.absorb.rgb * path);
    vec3 scattered = G.scatter.rgb * ambient * (1.0 - exp(-G.scatter.w * path));
    vec3 water = transmitted + scattered;

    vec3 Rv = reflect(-V, n);
    vec3 Rw = (G.invView * vec4(Rv, 0.0)).xyz;
    float hit;
    vec3 ssr = traceReflection(P + n * 0.05, Rv, hit);
    vec3 reflection = mix(skyColor(Rw), ssr, hit);
    float F = 0.02 + 0.98 * pow(1.0 - clamp(dot(n, V), 0.0, 1.0), 5.0);
    vec3 color = mix(water, reflection, F) + sunSpecular(n, V, footprint);
    // Wave crests are thinner, so light scattered in the water shows through them a little brighter.
    color += G.scatter.rgb * ambient * clamp(wk.z * 2.5, 0.0, 0.2);
    if (wk.w > 0.01) {
        float churn = foamTexture(world.xz * 1.4, wk.w * 0.85, footprint);
        color = mix(color, vec3(0.9, 0.94, 0.96) * mix(0.25, 1.0, ambient), churn * 0.75);
    }
    if (aer > 0.0) {
        float cover = sheet ? aer * (0.35 + 0.9 * smoothstep(0.3, 0.75, streaks)) : 0.9 * foamTexture(world.xz, aer, footprint);
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
        bool sheet = matTagged && matId > 0.115;
        float aer = matTagged ? clamp(sheet ? (matId - 0.12) / 0.1 : matId / 0.1, 0.0, 1.0) : 0.0;
        color = lavaPass ? shadeAboveLava(uv, g.xyz, fd, ambient) : shadeAboveWater(uv, g.xyz, fd, sceneDist, ambient, aer, sheet);
        vec4 clip = G.proj * vec4(viewPosAt(uv, fd), 1.0);
        outDepth = clip.z / clip.w;
    }

    if (foam.a > 1e-4) {
        vec3 foamColor = foam.rgb / foam.a;
        float coverage = min(1.0 - exp(-foam.a * G.foamA.y), 0.9);
        if (hasFluid && !underwater) {
            vec3 world = G.misc.yzw + (G.invView * vec4(viewPosAt(uv, fd), 0.0)).xyz;
            coverage *= foamLace(world.xz, coverage, pixelFootprint(fd));
        }
        color = mix(color, foamColor * mix(0.25, 1.0, ambient), coverage);
    }

    outColor = vec4(color, 1.0);
    gl_FragDepth = outDepth;
}
