#version 330
#extension GL_ARB_separate_shader_objects : require

// Each disc is shaded as the front half of a sphere. Red is where that surface is along the view
// axis (negative numbers, nearer is greater); blending keeps the greatest value, so the texture ends
// up with the nearest goo surface per pixel. Green is "there is goo here": 0 where the texture was
// cleared, 1 from every disc; smoothed later, it gives the goo an outline without the scallops of
// single discs.

layout(location = 0) in vec2 corner;
layout(location = 1) in vec3 centre;
layout(location = 2) in vec4 particle;
layout(location = 3) in float share;

layout(location = 0) out vec4 fragColor;

void main() {
    float r2 = dot(corner, corner);
    if (r2 > 1.0) {
        discard;
    }
    fragColor = vec4(centre.z + sqrt(1.0 - r2) * particle.x, 1.0, 0.0, 1.0);
}
