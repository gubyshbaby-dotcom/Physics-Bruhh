#version 330
#extension GL_ARB_separate_shader_objects : require

// Liquid look, first step: every particle of goo becomes a small disc facing the camera. The same
// vertex stage feeds two passes: the nearest surface per pixel (goo_depth) and how much goo lies
// along each line of sight (goo_thick). There are no vertex buffers: four vertices per particle,
// the particle itself is looked up by number.

#include <minecraft:projection.glsl>
#include <physicsbruhh:goo_frame.glsl>

uniform samplerBuffer GooParticles;   // xyz: position, w: size 0..255, +256 for slime, +512 * block light, +8192 * sky light, +131072 for a filler

layout(location = 0) out vec2 corner;      // -1..1 across the disc
layout(location = 1) out vec3 centre;      // of the particle, in camera space
layout(location = 2) out vec4 particle;    // radius, material (0 honey, 1 slime), block light 0..1, sky light 0..1
layout(location = 3) out float share;      // how much of a particle's worth of goo this one stands for

void main() {
    int k = gl_VertexIndex % 4;
    vec4 p = texelFetch(GooParticles, gl_VertexIndex / 4);
    int bits = int(p.w + 0.5);
    float size = float(bits & 255) / 255.0;
    float material = float((bits >> 8) & 1);
    float blockLight = float((bits >> 9) & 15) / 15.0;
    float skyLight = float((bits >> 13) & 15) / 15.0;
    // fillers only close gaps in the surface of goo that has been pulled long; they stand for hardly any goo
    bool filler = ((bits >> 17) & 1) == 1;

    vec2 c = vec2((k == 1 || k == 2) ? 1.0 : -1.0, k >= 2 ? 1.0 : -1.0);
    vec4 view = GooView * vec4(p.xyz + GooOffset.xyz, 1.0);
    float radius = GooOffset.w * size;

    corner = c;
    centre = view.xyz;
    particle = vec4(radius, material, blockLight, skyLight);
    share = filler ? 0.25 * size : size;
    // a particle that has shrunk to nothing is put outside the picture
    gl_Position = size > 0.004 ? ProjMat * (view + vec4(c * radius, 0.0, 0.0)) : vec4(2.0, 2.0, 2.0, 1.0);
}
