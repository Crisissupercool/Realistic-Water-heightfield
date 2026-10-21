// Shared definitions for the multi-layer heightfield solver (docs/HEIGHTFIELD_DESIGN.md).
// Binding 0 is the Globals UBO from common.glsl; per-dispatch parameters come in through push constants.
#include "common.glsl"

layout(push_constant) uniform HfPush {
    ivec4 dims;  // nx, nz, layers, spans
    vec4 p0;     // meaning depends on the shader, see the #defines in each file
    vec4 p1;
    ivec4 p2;
} hp;

#define HF_NX hp.dims.x
#define HF_NZ hp.dims.y
#define HF_LAYERS hp.dims.z
#define HF_SPANS hp.dims.w
#define HF_LINKS 8u

const uint HF_NONE = 0xFFFFFFFFu;

// A link is packed as target << 3 | reverseSlot (see HfTopology).
uint hfTarget(uint l) {
    return l >> 3;
}

uint hfRev(uint l) {
    return l & 7u;
}

// geo = (floor, ceil); unused layer slots have ceil < floor.
bool hfValid(vec2 geo) {
    return geo.y > geo.x;
}
