#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, step 4, between the rounds: the answer of the level under a level added to its pressure, spread smoothly
// between that level's cells. Beyond the last of them, towards the zero outside what is computed, the answer falls
// off to that zero; cells of it that are blocks are left out.

#include <physicsbruhh:gas.glsl>

uniform sampler2D PressureSampler;   // this level
uniform sampler2D UnderSampler;      // the level under it

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec3 c = gasCell(gasLevelGrid(GAS_LEVEL), pixel);
    ivec3 cell = c - gasLevelFrom(GAS_LEVEL);
    if (c.y < 0 || any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, gasLevelCells(GAS_LEVEL)))) { fragColor = vec4(0.0); return; }
    vec2 here = texelFetch(PressureSampler, pixel, 0).rg;
    if (here.r < GAS_WALL_TEST) { fragColor = vec4(here, 0.0, 0.0); return; }

    vec4 under = gasLevelGrid(GAS_LEVEL + 1);
    ivec3 cells = gasLevelCells(GAS_LEVEL + 1);
    // a cell that is not there stands for the zero outside: the last cell's value times this
    float outside = 1.0 - gasLevelEdge(GAS_LEVEL + 1);
    vec3 at = (vec3(cell) + 0.5) * 0.5 - 0.5;
    ivec3 low = ivec3(floor(at));
    vec3 t = at - vec3(low);
    float sum = 0.0, weight = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 corner = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 w3 = mix(1.0 - t, t, vec3(corner));
        float w = w3.x * w3.y * w3.z;
        ivec3 n = low + corner;
        float k = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            if (n[axis] < 0) { n[axis] = 0; k *= outside; }
            else if (n[axis] >= cells[axis]) { n[axis] = cells[axis] - 1; k *= outside; }
        }
        float v = texelFetch(UnderSampler, gasPixel(under, n), 0).r;
        if (v > GAS_WALL_TEST) { sum += v * k * w; weight += w; }
    }
    fragColor = vec4(here.r + (weight > 1.0e-4 ? sum / weight : 0.0), here.g, 0.0, 0.0);
}
