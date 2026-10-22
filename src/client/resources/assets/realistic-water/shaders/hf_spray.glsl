// Spray of the heightfield (see HfDomain): two sets of particles, each in two halves, in one buffer; pos.xyz relative to the
// heightfield origin (blocks, y from its yMin) with life in pos.w, vel.xyz (blocks/s) and in vel.w:
//  - set 0, spray (whitewater pass): the type, 0 a droplet, 3 a mist puff;
//  - set 1, splash water drops (whitewater pass too, as motion-blurred bright streaks): 4 + the drop's radius as a multiple of
//    hfDropRadius (types 0..3 are the spray's).
layout(std430, binding = 17) buffer Spray { Particle spray[]; };
// [0], [1]: spray in each half; [2], [3]: drops in each half; [4..7], [8..11]: draw arguments for the current half of each set
// (6 vertices, count, 0, first particle).
layout(std430, binding = 18) buffer SprayCount { uint sprayCount[]; };

const uint SPRAY_MAX = 65536u;
const uint DROP_MAX = 16384u;
const uint SET_SPRAY = 0u;
const uint SET_DROPS = 1u;
const float SPRAY_DROPLET = 0.0;
const float SPRAY_MIST = 3.0;

uint setMax(uint which) {
    return which == SET_SPRAY ? SPRAY_MAX : DROP_MAX;
}

// First particle of a half of a set in the buffer.
uint setBase(uint which, uint part) {
    return which == SET_SPRAY ? part * SPRAY_MAX : 2u * SPRAY_MAX + part * DROP_MAX;
}

uint sprayHash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

float sprayRand(inout uint s) {
    s = sprayHash(s + 0x9e3779b9u);
    return float(s >> 8) * (1.0 / 16777216.0);
}

void particleAdd(uint which, uint into, vec3 p, vec3 v, float life, float w) {
    uint i = atomicAdd(sprayCount[which * 2u + into], 1u);
    if (i < setMax(which)) {
        spray[setBase(which, into) + i] = Particle(vec4(p, life), vec4(v, w));
    }
}

void sprayAdd(uint into, vec3 p, vec3 v, float life, float type) {
    particleAdd(SET_SPRAY, into, p, v, life, type);
}

void dropAdd(uint into, vec3 p, vec3 v, float life, float size) {
    particleAdd(SET_DROPS, into, p, v, life, 4.0 + size);
}
