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
