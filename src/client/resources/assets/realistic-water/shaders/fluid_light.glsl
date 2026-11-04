layout(binding = LIGHTMAP_BINDING) uniform sampler2D lightmapTex;

vec3 vanillaLight(vec2 bs) {
    return texture(lightmapTex, (clamp(bs, 0.0, 1.0) * 15.0 + 0.5) / 16.0).rgb;
}
