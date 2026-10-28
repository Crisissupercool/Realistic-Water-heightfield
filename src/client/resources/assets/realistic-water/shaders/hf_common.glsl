// Shared definitions for the sparse multi-layer heightfield solver (docs/HEIGHTFIELD_DESIGN.md, sections 1, 12 and 13).
// Binding 0 is the Globals UBO from common.glsl; per-dispatch parameters come in through push constants.
//
// Water lives in 16x16-block tiles. Each used layer of a tile is one page of tn*tn spans (one per column), so
// span = page * pageSpans + col. Flux pages (8 floats per span) exist only while a tile is simulated. See HfTileGrid.
#include "common.glsl"

layout(push_constant) uniform HfPush {
    ivec4 dims;  // tn (columns per tile side), cells per block, yMin, number of entries in this dispatch
    vec4 p0;     // meaning depends on the shader, see the #defines in each file
    vec4 p1;
    ivec4 p2;
} hp;

#define HF_TN hp.dims.x
#define HF_CPB hp.dims.y
#define HF_YMIN hp.dims.z
#define HF_COUNT hp.dims.w
#define HF_LINKS 8u

layout(std430, binding = 1) readonly buffer Geo { uint geoU[]; };            // floorU | ceilU << 16 per span
layout(std430, binding = 4) readonly buffer Links { uint links[]; };         // 8 x 4 bits per span
layout(std430, binding = 5) readonly buffer PageList { uint pageList[]; };   // tile << 2 | layer, pages to simulate
layout(std430, binding = 6) readonly buffer TileInfo { ivec4 tileInfo[]; };  // 4 per tile: neighbors, (tx, tz, layers, 0), span pages, flux pages
layout(std430, binding = 7) readonly buffer TileActive { uint tileActive[]; };
layout(std430, binding = 13) readonly buffer PageOwner { int pageOwner[]; };  // tile << 2 | layer per span page, -1 when free

const uint HF_NONE = 0xFFFFFFFFu;
const uint HF_LINK_NONE = 0xFu;
const uint HF_OPEN_U = 0xFFFFu;
const float HF_OPEN_CEIL = 1.0e9;
const int HF_DX[4] = int[](1, -1, 0, 0);
const int HF_DZ[4] = int[](0, 0, 1, -1);

struct HfSpan {
    uint span;  // index into geo, depth and links
    uint flux;  // first of the span's 8 flux slots, or HF_NONE when its tile holds no flux pages
    uint tile;
    uint layer;
    uint col;
};

uint hfPageSpans() {
    return uint(HF_TN * HF_TN);
}

// Same arithmetic as HfTileGrid.floorOf / ceilOf, so CPU and GPU agree bit for bit.
float hfFloor(uint g) {
    return float(HF_YMIN) + float(g & 0xFFFFu) / float(HF_CPB);
}

float hfCeil(uint g) {
    uint c = g >> 16;
    return c == HF_OPEN_U ? HF_OPEN_CEIL : float(HF_YMIN) + float(c) / float(HF_CPB);
}

bool hfValidGeo(uint g) {
    return (g >> 16) > (g & 0xFFFFu);
}

// (floor, ceil); unused layers have ceil < floor.
vec2 hfGeo(uint s) {
    uint g = geoU[s];
    return hfValidGeo(g) ? vec2(hfFloor(g), hfCeil(g)) : vec2(1.0e30, -1.0e30);
}

bool hfValid(vec2 geo) {
    return geo.y > geo.x;
}

HfSpan hfMake(uint tile, uint layer, uint col, uint spanPage) {
    HfSpan r;
    r.tile = tile;
    r.layer = layer;
    r.col = col;
    r.span = spanPage * hfPageSpans() + col;
    int fp = tileInfo[tile * 4u + 3u][layer];
    r.flux = fp < 0 ? HF_NONE : (uint(fp) * hfPageSpans() + col) * HF_LINKS;
    return r;
}

// Invocation gid over the page list (solver passes).
bool hfFromList(uint gid, out HfSpan r) {
    uint idx = gid / hfPageSpans();
    if (idx >= uint(HF_COUNT)) {
        return false;
    }
    uint e = pageList[idx];
    uint tile = e >> 2;
    uint layer = e & 3u;
    r = hfMake(tile, layer, gid % hfPageSpans(), uint(tileInfo[tile * 4u + 2u][layer]));
    return true;
}

// Invocation gid over every span page (mesh pass): pages that belong to no tile are skipped.
bool hfFromPage(uint gid, out HfSpan r) {
    uint page = gid / hfPageSpans();
    if (page >= uint(HF_COUNT)) {
        return false;
    }
    int owner = pageOwner[page];
    if (owner < 0) {
        return false;
    }
    r = hfMake(uint(owner) >> 2, uint(owner) & 3u, gid % hfPageSpans(), page);
    return true;
}

uint hfLink(HfSpan a, uint k) {
    return (links[a.span] >> (k * 4u)) & 0xFu;
}

// Reverse link slot on the neighbor's side.
uint hfRev(uint k, uint nib) {
    return ((k >> 1) ^ 1u) * 2u + (nib >> 2);
}

ivec2 hfColumn(uint col) {
    return ivec2(int(col % uint(HF_TN)), int(col / uint(HF_TN)));
}

// Span reached through link nibble nib in slot k of a. False when the neighbor tile does not exist (or, with requireActive,
// is not simulated this frame: then the border acts as a wall and the volume stays exact).
bool hfNeighbor(HfSpan a, uint k, uint nib, bool requireActive, out HfSpan b) {
    ivec2 c = hfColumn(a.col);
    uint d = k >> 1;
    c += ivec2(HF_DX[d], HF_DZ[d]);
    int tile = int(a.tile);
    if (c.x < 0 || c.y < 0 || c.x >= HF_TN || c.y >= HF_TN) {
        tile = tileInfo[a.tile * 4u][d];
        if (tile < 0) {
            return false;
        }
        c = (c + HF_TN) % HF_TN;
    }
    if (requireActive && tileActive[tile] == 0u) {
        return false;
    }
    uint layer = nib & 3u;
    int page = tileInfo[uint(tile) * 4u + 2u][layer];
    if (page < 0) {
        return false;
    }
    b = hfMake(uint(tile), layer, uint(c.y * HF_TN + c.x), uint(page));
    return true;
}

// Foam (aeration, 0..1) per simulated span, kept on the flux pages: two halves that swap every solver step, because the
// depth pass reads its neighbors' foam while writing its own. Spans of tiles that are not simulated have no foam.
layout(std430, binding = 15) buffer Foam { float foam[]; };

uint hfFoamIndex(HfSpan a) {
    return a.flux / HF_LINKS;
}

// Water in the air (hf_depth.comp): water pouring into a span from one whose floor is more than HF_AIR_MIN_DROP above its surface
// falls for a while before it lands. Per span page span, 4 floats: volume in flight (blocks^3), seconds since it began falling in,
// its mean drop (blocks), and how much of the span's water is in the drawn surface (hfShown: its presence 0..1, plus 2 while
// it counts as running; hf_depth.comp moves it toward hfPresenceTarget, hf_mesh.comp draws by it). Kept with the span pages,
// so it survives a tile falling asleep.
layout(std430, binding = 20) buffer Air { float air[]; };
const float HF_AIR_MIN_DROP = 1.5;
const uint HF_AIR_STRIDE = 4u;
// A thin film (deeper than HF_FILM_DRY, thinner than hfRenderDepth) is drawn while it runs (outflow above HF_FILM_DRAW,
// blocks^3/s). Its outflow hovers around that on terraces and slopes, and judged frame by frame such spans switched between drawn
// and hidden up to 2 times a second each (half-block pieces of water popping in and out). A running film now keeps running
// down to 0.4 x that outflow (or inflow: a front's edge only fills at first).
// Presence is the coverage hf_mesh.comp's marching-squares shoreline interpolates: still water from hfRenderDepth (fully
// STILL_FADE deeper), running water from HF_FILM_DRY (fully at HF_FILM_FULL). It moves toward that by at most 1 per
// HF_FILM_SWITCH seconds, so the waterline crosses a half-block cell in no less than that: a flood front's edge fills from 0 to
// 4 cm within a frame or two, and judged by depth alone the line still jumped a quarter block at a time.
const float HF_FILM_DRY = 0.008;
const float HF_FILM_DRAW = 0.0005;
const float HF_FILM_FULL = 0.016;
const float HF_STILL_FADE = 0.01;
const float HF_FILM_SWITCH = 0.3;
#define HF_RENDER_DEPTH G.simB.w
float hfPresenceTarget(float d, bool running) {
    float still = clamp((d - HF_RENDER_DEPTH) / HF_STILL_FADE, 0.0, 1.0);
    float film = running ? clamp((d - HF_FILM_DRY) / (HF_FILM_FULL - HF_FILM_DRY), 0.0, 1.0) : 0.0;
    return max(still, film);
}
float hfShown(float v) {
    return v >= 2.0 ? v - 2.0 : v;
}
// Falling water breaks up and air holds it back toward a terminal speed (blocks/s); a high fall takes about 3.3 s for 30 blocks
// instead of 2.5 in free fall, and is never instant.
const float HF_AIR_TERMINAL = 12.0;
const float HF_AIR_G = 9.81;

// Time to fall h blocks with quadratic drag: t = vt/g acosh(e^(g h / vt^2)), written so it cannot overflow for tall drops.
float hfFallTime(float h) {
    float a = HF_AIR_G * max(h, 0.0) / (HF_AIR_TERMINAL * HF_AIR_TERMINAL);
    return HF_AIR_TERMINAL / HF_AIR_G * (a + log(1.0 + sqrt(max(1.0 - exp(-2.0 * a), 0.0))));
}

// Distance fallen after t seconds: vt^2/g ln cosh(g t / vt), likewise without overflow.
float hfFallDistance(float t) {
    float b = HF_AIR_G * max(t, 0.0) / HF_AIR_TERMINAL;
    return HF_AIR_TERMINAL * HF_AIR_TERMINAL / HF_AIR_G * (b + log(1.0 + exp(-2.0 * b)) - 0.6931472);
}

// Bodies moving through the water (G.caps.z entries of G.ent, written by HfWorld.stir): x, z relative to the domain origin
// and y absolute, in blocks; ent[0].w is the radial push speed of a body crossing the surface, ent[1].w the strength.
// Returns the velocity (blocks/s) they push this column's water at, and in z how much of the column's height they fill.
const float HF_STIR_REACH = 0.35;

vec3 hfStir(HfSpan a, float floorY, float surfaceY) {
    vec3 r = vec3(0.0);
    int n = min(G.caps.z, MAX_ENTITIES);
    if (n <= 0) {
        return r;
    }
    ivec2 t = tileInfo[a.tile * 4u + 1u].xy;
    vec2 p = (vec2(t * HF_TN + hfColumn(a.col)) + 0.5) / float(HF_CPB);
    for (int e = 0; e < n; e++) {
        vec4 lo = G.ent[e * 3];
        vec4 hi = G.ent[e * 3 + 1];
        vec2 d = p - 0.5 * (lo.xz + hi.xz);
        float dist = length(d);
        float radius = 0.5 * max(hi.x - lo.x, hi.z - lo.z);
        float w = 1.0 - smoothstep(radius, radius + HF_STIR_REACH, dist);
        float sub = min(hi.y, surfaceY) - max(lo.y, floorY);
        if (w <= 0.0 || sub <= 0.0) {
            continue;
        }
        // A body well below the surface hardly moves it.
        w *= clamp(1.0 - (surfaceY - hi.y) / 1.5, 0.0, 1.0);
        vec2 v = G.ent[e * 3 + 2].xz;
        if (dist > 0.05) {
            v += d / dist * lo.w;
        }
        r.xy += v * (w * hi.w);
        r.z = max(r.z, sub);
    }
    return r;
}
