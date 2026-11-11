#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, step 4, a round of the pressure solve on one of its levels: every cell moves towards the pressure its
// neighbours and what is to be undone in it (green) ask for. A block is not asked; beyond the cells in use the
// pressure is zero.

#include <physicsbruhh:gas.glsl>

uniform sampler2D PressureSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec3 c = gasCell(gasLevelGrid(GAS_LEVEL), pixel);
    ivec3 cell = c - gasLevelFrom(GAS_LEVEL);
    if (c.y < 0 || any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, gasLevelCells(GAS_LEVEL)))) { fragColor = vec4(0.0); return; }
    vec2 here = texelFetch(PressureSampler, pixel, 0).rg;
    if (here.r < GAS_WALL_TEST) { fragColor = vec4(here, 0.0, 0.0); return; }
    vec2 ties = gasTies(PressureSampler, GAS_LEVEL, cell);
    float asked = ties.y > 0.0 ? (ties.x - gasLevelCell2(GAS_LEVEL) * here.g) / ties.y : here.r;
    fragColor = vec4(here.r + GAS_ROUND_WEIGHT * (asked - here.r), here.g, 0.0, 0.0);
}
