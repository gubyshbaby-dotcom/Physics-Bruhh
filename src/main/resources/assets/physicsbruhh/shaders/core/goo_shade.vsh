#version 330
#extension GL_ARB_separate_shader_objects : require

// The shade goo throws on what lies under it: every particle seen from straight above, as a soft
// disc, into a texture that covers the patch of ground under all the goo (GooShade). The picture
// pass looks up, for every bit of the world it shows, how much goo hangs over it.

#include <physicsbruhh:goo_frame.glsl>

uniform samplerBuffer GooParticles;   // as in goo_splat.vsh

layout(location = 0) out vec2 corner;   // -1..1 across the disc
layout(location = 1) out vec4 goo;      // how much of a particle's worth this one stands for, material, height of its underside, radius of the disc

void main() {
    int k = gl_VertexIndex % 4;
    vec4 p = texelFetch(GooParticles, gl_VertexIndex / 4);
    int bits = int(p.w + 0.5);
    float size = float(bits & 255) / 255.0;
    float material = float((bits >> 8) & 1);
    bool filler = ((bits >> 17) & 1) == 1;

    vec2 c = vec2((k == 1 || k == 2) ? 1.0 : -1.0, k >= 2 ? 1.0 : -1.0);
    float radius = GooOffset.w * 1.6 * size;
    corner = c;
    goo = vec4(filler ? 0.25 * size : size, material, p.y - 0.5 * GooLight.a, radius);
    vec2 at = (p.xz + c * radius - GooShade.xy) * GooShade.zw;
    gl_Position = size > 0.004 ? vec4(at * 2.0 - 1.0, 0.0, 1.0) : vec4(2.0, 2.0, 2.0, 1.0);
}
