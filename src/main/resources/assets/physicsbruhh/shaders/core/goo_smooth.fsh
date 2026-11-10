#version 330
#extension GL_ARB_separate_shader_objects : require

// Smooths the amount-of-goo texture: particles are lumps in rows, and without this the rows show
// through as stripes in the colour. One direction per pass (HORIZONTAL or not), reaching about two
// particle spacings at the distance of the goo in this pixel.

#include <minecraft:projection.glsl>
#include <physicsbruhh:goo_frame.glsl>

uniform sampler2D InSampler;      // the amounts
uniform sampler2D FluidSampler;   // nearest goo surface, for the distance

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    float along = -texelFetch(FluidSampler, ivec2(gl_FragCoord.xy), 0).r;
    if (along > GOO_NONE) {
        // no surface here, so nothing will be drawn here; the amounts are left for the neighbours
        fragColor = texture(InSampler, texCoord);
        return;
    }
    float reach = clamp(2.2 * GooLight.a * 0.5 * abs(ProjMat[1][1]) * size.y / along, 1.0, 40.0);
#ifdef HORIZONTAL
    vec2 dir = vec2(reach / (6.0 * size.x), 0.0);
#else
    vec2 dir = vec2(0.0, reach / (6.0 * size.y));
#endif
    vec4 sum = texture(InSampler, texCoord);
    float weight = 1.0;
    for (int i = 1; i <= 6; i++) {
        float fi = float(i);
        float g = exp(-fi * fi * 0.085);
        sum += (texture(InSampler, texCoord + dir * fi) + texture(InSampler, texCoord - dir * fi)) * g;
        weight += 2.0 * g;
    }
    fragColor = sum / weight;
}
