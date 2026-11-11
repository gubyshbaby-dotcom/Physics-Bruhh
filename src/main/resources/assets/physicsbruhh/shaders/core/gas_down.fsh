#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, step 4, between the rounds: what the pressure of a level still leaves undone, handed to the level under it
// (the one this pass writes): the mean over the eight cells each of its cells covers, in green. That level starts
// from no pressure (red), and a cell of it counts as a block (marked in red) if at least half of what it covers is.

#include <physicsbruhh:gas.glsl>

uniform sampler2D PressureSampler;   // the level above

layout(location = 0) out vec4 fragColor;

void main() {
    ivec3 c = gasCell(gasLevelGrid(GAS_LEVEL), ivec2(gl_FragCoord.xy));
    if (c.y < 0 || any(greaterThanEqual(c, gasLevelCells(GAS_LEVEL)))) { fragColor = vec4(0.0); return; }
    vec4 above = gasLevelGrid(GAS_LEVEL - 1);
    ivec3 from = gasLevelFrom(GAS_LEVEL - 1), cells = gasLevelCells(GAS_LEVEL - 1);
    float cell2 = gasLevelCell2(GAS_LEVEL - 1);
    int open = 0, blocks = 0;
    float left = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 covered = c * 2 + ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        if (any(greaterThanEqual(covered, cells))) continue;
        vec2 there = texelFetch(PressureSampler, gasPixel(above, from + covered), 0).rg;
        if (there.r < GAS_WALL_TEST) { blocks++; continue; }
        open++;
        vec2 ties = gasTies(PressureSampler, GAS_LEVEL - 1, covered);
        left += 0.125 * (there.g - (ties.x - ties.y * there.r) / cell2);
    }
    fragColor = blocks >= open ? vec4(GAS_WALL, 0.0, 0.0, 0.0) : vec4(0.0, left, 0.0, 0.0);
}
