#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Verts { vec4 verts[]; };
layout(std430, binding = 2) readonly buffer Quads { uvec4 quads[]; };
layout(location = 0) out vec3 vView;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out float vWall;
layout(location = 3) out float vOpen; // heightfield faces: no closing back face, mesh_thickness.frag closes at scene depth
layout(location = 4) out float vAer;  // heightfield aeration (foam, falling water), 0 for the fluid meshes

const int CORNER[6] = int[](0, 1, 2, 0, 2, 3);
const uint OPEN = 0x80000000u;

// Heightfield vertex format (hf_mesh.comp hfPack): 12 + 12 bit octahedral normal, 6 bit aeration, wall flag, OPEN.
vec3 unpackHf(uint v, out bool wall, out float aer) {
    wall = (v & 0x40000000u) != 0u;
    aer = float((v >> 24) & 63u) / 63.0;
    vec2 e = vec2(float(v & 0xFFFu), float((v >> 12) & 0xFFFu)) / 4095.0 * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

void main() {
    uvec4 q = quads[gl_VertexIndex / 6];
    vec4 v = verts[q[CORNER[gl_VertexIndex % 6]]];
    uint bits = floatBitsToUint(v.w);
    bool open = (bits & OPEN) != 0u;
    bool wall;
    float aer = 0.0;
    vec3 n = open ? unpackHf(bits, wall, aer) : unpackNormal(bits, wall);
    vec4 view = G.view * vec4(G.domainOrigin.xyz + v.xyz * G.domainOrigin.w, 1.0);
    vView = view.xyz;
    vNormal = mat3(G.view) * n;
    vWall = wall ? 1.0 : 0.0;
    vOpen = open ? 1.0 : 0.0;
    vAer = aer;
    gl_Position = G.proj * view;
}
