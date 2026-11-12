#version 330
#extension GL_ARB_separate_shader_objects : require

// What the surface of the lava is like at each pixel: the particles that lie at the surface (not
// those behind it) each add what they carry, weighted by how near the surface they are and soft
// towards their rim. Blending adds up: red is the pattern, green the heat, blue the weight; the
// picture pass divides. Alpha keeps the greatest value instead: every particle at the surface is a
// small dome there, each a little higher or lower than the next, so what is left is a grain of
// lumps with gaps between them. So the crust is made of something that belongs to the lava and
// moves, stretches and tears with it, instead of a picture the lava slides under.

#include <physicsbruhh:goo_frame.glsl>

uniform sampler2D FluidSampler;   // nearest surface per pixel, smoothed: r minus its distance along the view axis

layout(location = 0) in vec2 corner;
layout(location = 1) in vec3 centre;
layout(location = 2) in vec4 particle;
layout(location = 3) in float share;

layout(location = 0) out vec4 fragColor;

void main() {
    float k = 1.0 - dot(corner, corner);
    if (k <= 0.0) {
        discard;
    }
    float surface = -texelFetch(FluidSampler, ivec2(gl_FragCoord.xy), 0).r;
    float mine = -(centre.z + sqrt(k) * particle.x);
    // how far behind the surface this one is, in particle spacings
    float behind = (mine - surface) / GooLight.a;
    if (behind > 2.5) {
        discard;
    }
    float near = exp(-behind * behind * 0.9);
    float w = k * k * near * share;
    // the lump: narrower than the disc, so that there are gaps between neighbours; no two are the
    // same size or sit in the middle of their particle, or the rows the particles lie in would show
    float own = particle.w;
    vec2 off = corner - 0.32 * vec2(cos(own * 61.0), sin(own * 61.0));
    float lump = max(1.0 - dot(off, off) * mix(1.7, 4.2, fract(own * 7.31)), 0.0);
    fragColor = vec4(particle.y * w, particle.z * w, w, lump * sqrt(lump) * near * (0.45 + 0.55 * fract(own * 3.17)));
}
