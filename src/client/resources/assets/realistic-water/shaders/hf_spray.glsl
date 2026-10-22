// Spray of the heightfield (see HfDomain): whitewater particles in two halves of SPRAY_MAX in one buffer, in the particle
// regions' whitewater layout so the whitewater pass draws them: pos.xyz relative to the heightfield origin (blocks, y from its
// yMin), life in pos.w; vel.xyz (blocks/s), type in vel.w: 0 a droplet, 3 a mist puff.
layout(std430, binding = 17) buffer Spray { Particle spray[]; };
// [0], [1]: particles in each half; [4..7]: draw arguments for the current half (6 vertices, count, 0, first particle).
layout(std430, binding = 18) buffer SprayCount { uint sprayCount[]; };

const uint SPRAY_MAX = 65536u;
const float SPRAY_DROPLET = 0.0;
const float SPRAY_MIST = 3.0;

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

void sprayAdd(uint into, vec3 p, vec3 v, float life, float type) {
    uint i = atomicAdd(sprayCount[into], 1u);
    if (i < SPRAY_MAX) {
        spray[into * SPRAY_MAX + i] = Particle(vec4(p, life), vec4(v, type));
    }
}
