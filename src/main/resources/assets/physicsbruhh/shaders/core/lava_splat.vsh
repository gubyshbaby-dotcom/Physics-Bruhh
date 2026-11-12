#version 330
#extension GL_ARB_separate_shader_objects : require

// Lava that has left its block and flows as a body of its own is drawn from the particles of the
// simulation, the way clear goo is: every particle a small disc facing the camera, the discs merged
// into one surface on the screen. This is the first step, the discs. (The same shape as goo_splat,
// so the passes that merge goo into a surface work on lava unchanged; what differs is what a
// particle of lava says about itself.)

#include <minecraft:projection.glsl>
#include <physicsbruhh:goo_frame.glsl>

uniform samplerBuffer LavaParticles;   // xyz: position, w: size 0..255, +256 * pattern (0..63), +16384 * heat (0..63), +1048576 for a filler

layout(location = 0) out vec2 corner;      // -1..1 across the disc
layout(location = 1) out vec3 centre;      // of the particle, in camera space
layout(location = 2) out vec4 particle;    // radius, its pattern 0..1, its heat 0..1, a number of its own 0..1
layout(location = 3) out float share;      // how much of a particle's worth of lava this one stands for

void main() {
    int k = gl_VertexIndex % 4;
    vec4 p = texelFetch(LavaParticles, gl_VertexIndex / 4);
    int bits = int(p.w);
    float size = float(bits & 255) / 255.0;
    float pattern = float((bits >> 8) & 63) / 63.0;
    float heat = float((bits >> 14) & 63) / 63.0;
    bool filler = ((bits >> 20) & 1) == 1;

    vec2 c = vec2((k == 1 || k == 2) ? 1.0 : -1.0, k >= 2 ? 1.0 : -1.0);
    vec4 view = GooView * vec4(p.xyz + GooOffset.xyz, 1.0);
    float radius = GooOffset.w * size;

    corner = c;
    centre = view.xyz;
    particle = vec4(radius, pattern, heat, fract(sin(float(gl_VertexIndex / 4) * 12.9898) * 43758.5453));
    share = filler ? 0.25 * size : size;
    // a particle that has shrunk to nothing is put outside the picture
    gl_Position = size > 0.004 ? ProjMat * (view + vec4(c * radius, 0.0, 0.0)) : vec4(2.0, 2.0, 2.0, 1.0);
}
