#version 330
#extension GL_ARB_separate_shader_objects : require

// How much goo lies along each line of sight: every particle adds its share, soft towards its rim.
// Blending adds up. Red is honey, green is slime (in blocks); blue and alpha carry the same amount
// weighted by the block light and sky light where the particle is, so the picture knows how
// brightly lit the goo in each pixel is.

#include <physicsbruhh:goo_frame.glsl>

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
    // a particle stands for a cube of goo one spacing wide; spread over the disc with this falloff
    // that comes to 3 s^3 / (pi r^2) in the middle
    float s = GooLight.a;
    float amount = k * k * 3.0 * s * s * s * share / (3.14159265 * particle.x * particle.x);
    fragColor = vec4(amount * (1.0 - particle.y), amount * particle.y, amount * particle.z, amount * particle.w);
}
