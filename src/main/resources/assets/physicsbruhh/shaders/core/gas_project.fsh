#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, step 5: the velocity with the pressure taken off, and nothing of it left pointing into a block.

#include <physicsbruhh:gas.glsl>

uniform sampler2D VelSampler;        // step 2
uniform sampler2D PressureSampler;   // step 4

layout(location = 0) out vec4 fragColor;

// x: the pressure to count there, y: 1 if that is a block
vec2 beside(ivec3 cell, float own) {
    if (!gasComputed(GasCoarse, cell)) return vec2(0.0);
    float v = texelFetch(PressureSampler, gasPixel(GasCoarse, cell), 0).r;
    return v < GAS_WALL_TEST ? vec2(own, 1.0) : vec2(v, 0.0);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec3 c = gasCell(GasCoarse, pixel);
    float own = texelFetch(PressureSampler, pixel, 0).r;
    if (c.y < 0 || !gasComputed(GasCoarse, c) || own < GAS_WALL_TEST) { fragColor = vec4(0.0); return; }
    vec2 xr = beside(c + ivec3(1, 0, 0), own), xl = beside(c - ivec3(1, 0, 0), own);
    vec2 yr = beside(c + ivec3(0, 1, 0), own), yl = beside(c - ivec3(0, 1, 0), own);
    vec2 zr = beside(c + ivec3(0, 0, 1), own), zl = beside(c - ivec3(0, 0, 1), own);
    vec3 a = texelFetch(VelSampler, pixel, 0).xyz - vec3(xr.x - xl.x, yr.x - yl.x, zr.x - zl.x) * 0.5 * GasStep.z;
    if ((a.x > 0.0 && xr.y > 0.5) || (a.x < 0.0 && xl.y > 0.5)) a.x = 0.0;
    if ((a.y > 0.0 && yr.y > 0.5) || (a.y < 0.0 && yl.y > 0.5)) a.y = 0.0;
    if ((a.z > 0.0 && zr.y > 0.5) || (a.z < 0.0 && zl.y > 0.5)) a.z = 0.0;
    fragColor = vec4(a, 0.0);
}
