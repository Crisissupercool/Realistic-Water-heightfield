#version 450
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Verts { vec4 verts[]; };
layout(std430, binding = 2) readonly buffer Quads { uvec4 quads[]; };
layout(location = 0) out vec3 vView;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out float vWall;
layout(location = 3) out float vOpen; // heightfield faces: no closing back face, mesh_thickness.frag closes at scene depth
layout(location = 4) out float vAer;  // heightfield aeration (foam, falling water), 0 for the fluid meshes
// Heightfield quads: all four corner normals and aerations plus the position inside the quad, so mesh_front.frag can blend
// them bilinearly. Interpolated per triangle, each half-block cell showed a diagonal crease in shading and foam.
layout(location = 5) flat out vec3 vN0;
layout(location = 6) flat out vec3 vN1;
layout(location = 7) flat out vec3 vN2;
layout(location = 8) flat out vec3 vN3;
layout(location = 9) flat out vec4 vAer4;
layout(location = 10) out vec2 vQuadUV;
layout(location = 11) flat out float vBilinear;

const int CORNER[6] = int[](0, 1, 2, 0, 2, 3);
const vec2 CORNER_UV[4] = vec2[](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
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
    int cornerIndex = CORNER[gl_VertexIndex % 6];
    vec4 v = verts[q[cornerIndex]];
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
    vQuadUV = CORNER_UV[cornerIndex];
    vBilinear = open ? 1.0 : 0.0;
    vec3 cn[4];
    float ca[4];
    for (int i = 0; i < 4; i++) {
        bool w;
        float a = 0.0;
        uint cb = floatBitsToUint(verts[q[i]].w);
        cn[i] = mat3(G.view) * (open ? unpackHf(cb, w, a) : n);
        ca[i] = a;
    }
    vN0 = cn[0];
    vN1 = cn[1];
    vN2 = cn[2];
    vN3 = cn[3];
    vAer4 = vec4(ca[0], ca[1], ca[2], ca[3]);
    gl_Position = G.proj * view;
}
