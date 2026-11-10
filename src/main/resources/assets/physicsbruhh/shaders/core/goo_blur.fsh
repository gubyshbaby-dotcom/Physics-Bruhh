#version 330
#extension GL_ARB_separate_shader_objects : require

// Smooths the nearest-surface texture so that a heap of little spheres turns into one liquid
// surface. One direction per pass (HORIZONTAL or not); RADIUS is how far it reaches, in particle
// spacings. The blur does not reach across edges: goo much further back is left out, goo much
// nearer is held back, so drops in front of a puddle keep their own outline.
// With OUTLINE (the last, shortest round) the "goo here" channel is smoothed as well, plainly:
// that rounds off the outline, which otherwise shows every single disc.

#include <minecraft:projection.glsl>
#include <physicsbruhh:goo_frame.glsl>

uniform sampler2D InSampler;   // r: minus the distance of the goo surface along the view axis, g: 1 where there is goo

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const int TAPS = 12;

void main() {
    ivec2 here = ivec2(gl_FragCoord.xy);
    vec2 own = texelFetch(InSampler, here, 0).rg;
    float d0 = -own.r;
    if (d0 > GOO_NONE) {
        fragColor = vec4(-2.0 * GOO_NONE, 0.0, 0.0, 1.0);
        return;
    }
    ivec2 last = textureSize(InSampler, 0) - 1;
    float s = GooLight.a;
    // pixels per block at this distance
    float scale = 0.5 * abs(ProjMat[1][1]) * float(last.y + 1) / d0;
    float reach = clamp(float(RADIUS) * s * scale, 1.0, 0.16 * float(last.y + 1));
    float stride = reach / float(TAPS);
#ifdef HORIZONTAL
    vec2 dir = vec2(1.0, 0.0);
#else
    vec2 dir = vec2(0.0, 1.0);
#endif
    float sum = d0;
    float weight = 1.0;
    float present = own.g;
    float all = 1.0;
    for (int i = 1; i <= TAPS; i++) {
        float fi = float(i);
        float g = exp(-fi * fi * (2.7 / float(TAPS * TAPS)));
        for (int side = -1; side <= 1; side += 2) {
            ivec2 at = clamp(here + ivec2(round(dir * fi * stride * float(side))), ivec2(0), last);
            vec2 there = texelFetch(InSampler, at, 0).rg;
            present += there.g * g;
            all += g;
            float d = -there.r;
            if (d > d0 + 6.0 * s) {
                continue;
            }
            d = max(d, d0 - s);
            sum += d * g;
            weight += g;
        }
    }
#ifdef OUTLINE
    fragColor = vec4(-sum / weight, present / all, 0.0, 1.0);
#else
    fragColor = vec4(-sum / weight, own.g, 0.0, 1.0);
#endif
}
