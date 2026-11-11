#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, step 4, many times over: the pressure that undoes the piling up (one Jacobi round).
// A block has the pressure of the cell beside it, so nothing is pushed into it; beyond the edge of the box it is zero.

#include <physicsbruhh:gas.glsl>

uniform sampler2D PressureSampler;

layout(location = 0) out vec4 fragColor;

float beside(ivec3 cell, float own) {
    if (!gasComputed(GasCoarse, cell)) return 0.0;
    float v = texelFetch(PressureSampler, gasPixel(GasCoarse, cell), 0).r;
    return v < GAS_WALL_TEST ? own : v;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec3 c = gasCell(GasCoarse, pixel);
    vec2 here = texelFetch(PressureSampler, pixel, 0).rg;
    if (c.y < 0 || !gasComputed(GasCoarse, c)) { fragColor = vec4(0.0); return; }
    if (here.r < GAS_WALL_TEST) { fragColor = vec4(here, 0.0, 0.0); return; }
    float sum = beside(c + ivec3(1, 0, 0), here.r) + beside(c - ivec3(1, 0, 0), here.r) + beside(c + ivec3(0, 1, 0), here.r)
              + beside(c - ivec3(0, 1, 0), here.r) + beside(c + ivec3(0, 0, 1), here.r) + beside(c - ivec3(0, 0, 1), here.r);
    fragColor = vec4((sum - here.g / (GasStep.z * GasStep.z)) / 6.0, here.g, 0.0, 0.0);
}
