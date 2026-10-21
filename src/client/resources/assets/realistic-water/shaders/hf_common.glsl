// Shared definitions for the sparse multi-layer heightfield solver (docs/HEIGHTFIELD_DESIGN.md, sections 1 and 12).
// Binding 0 is the Globals UBO from common.glsl; per-dispatch parameters come in through push constants.
//
// Water lives in 16x16-block tiles held in a pool of slots (HfTileGrid). A dispatch covers a list of slots; span index
// = slot * tileSpans + (lz * tn + lx) * layers + layer. Links are one byte each (targetLayer << 3 | reverseSlot, 0xFF = none),
// the target column being implied by the link slot's direction, so a link resolves across tile borders through tileInfo.
#include "common.glsl"

layout(push_constant) uniform HfPush {
    ivec4 dims;  // tn (columns per tile side), layers, tileSpans, number of slots in this dispatch
    vec4 p0;     // meaning depends on the shader, see the #defines in each file
    vec4 p1;
    ivec4 p2;
} hp;

#define HF_TN hp.dims.x
#define HF_LAYERS hp.dims.y
#define HF_TILE_SPANS hp.dims.z
#define HF_LIST_COUNT hp.dims.w
#define HF_LINKS 8u

layout(std430, binding = 4) readonly buffer Links { uint links[]; };          // 2 per span
layout(std430, binding = 5) readonly buffer TileList { uint tileList[]; };    // slots to process in this dispatch
layout(std430, binding = 6) readonly buffer TileInfo { ivec4 tileInfo[]; };   // [slot*2] = neighbor slot per dir (-1 none), [slot*2+1].xy = tile coords
layout(std430, binding = 7) readonly buffer TileActive { uint tileActive[]; };// 1 when the slot is simulated this frame

const uint HF_NONE = 0xFFFFFFFFu;
const uint HF_LINK_NONE = 0xFFu;
const int HF_DX[4] = int[](1, -1, 0, 0);
const int HF_DZ[4] = int[](0, 0, 1, -1);

// Span handled by this invocation: list mode maps through tileList, otherwise slots 0..count-1 in order.
uint hfSpanOf(uint gid, bool list) {
    uint ts = uint(HF_TILE_SPANS);
    uint idx = gid / ts;
    if (idx >= uint(HF_LIST_COUNT)) {
        return HF_NONE;
    }
    uint slot = list ? tileList[idx] : idx;
    return slot * ts + gid % ts;
}

uint hfLink(uint s, uint k) {
    return (links[s * 2u + (k >> 2)] >> ((k & 3u) * 8u)) & 0xFFu;
}

uint hfRev(uint l) {
    return l & 7u;
}

ivec2 hfColumn(uint s) {
    uint col = (s % uint(HF_TILE_SPANS)) / uint(HF_LAYERS);
    return ivec2(int(col % uint(HF_TN)), int(col / uint(HF_TN)));
}

// Span reached through link byte l in slot k of span s, or HF_NONE when the neighbor tile does not exist
// (or, with requireActive, is not simulated this frame: then the border acts as a wall and the volume stays exact).
uint hfNeighbor(uint s, uint k, uint l, bool requireActive) {
    uint ts = uint(HF_TILE_SPANS);
    uint slot = s / ts;
    ivec2 c = hfColumn(s);
    uint d = k >> 1;
    c += ivec2(HF_DX[d], HF_DZ[d]);
    int nslot = int(slot);
    if (c.x < 0 || c.y < 0 || c.x >= HF_TN || c.y >= HF_TN) {
        nslot = tileInfo[slot * 2u][d];
        if (nslot < 0) {
            return HF_NONE;
        }
        c = (c + HF_TN) % HF_TN;
    }
    if (requireActive && tileActive[nslot] == 0u) {
        return HF_NONE;
    }
    return uint(nslot) * ts + uint(c.y * HF_TN + c.x) * uint(HF_LAYERS) + (l >> 3);
}

// geo = (floor, ceil); unused layer slots have ceil < floor.
bool hfValid(vec2 geo) {
    return geo.y > geo.x;
}
