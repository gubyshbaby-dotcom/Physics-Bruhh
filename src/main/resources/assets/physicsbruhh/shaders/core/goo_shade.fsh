#version 330
#extension GL_ARB_separate_shader_objects : require

// Red and green add up to how much honey and slime hangs over each spot of the ground (in blocks);
// alpha keeps the greatest of minus the underside's height, that is, where the lowest goo over the
// spot is: only what lies below that is in its shade.

#include <physicsbruhh:goo_frame.glsl>

layout(location = 0) in vec2 corner;
layout(location = 1) in vec4 goo;

layout(location = 0) out vec4 fragColor;

void main() {
    float k = 1.0 - dot(corner, corner);
    if (k <= 0.0) {
        discard;
    }
    float s = GooLight.a;
    float amount = k * k * 3.0 * s * s * s * goo.x / (3.14159265 * goo.w * goo.w);
    fragColor = vec4(amount * (1.0 - goo.y), amount * goo.y, 0.0, -goo.z);
}
