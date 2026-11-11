#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, step 1: how the air spins in each coarse cell (the curl of the velocity), and how fast.

#include <physicsbruhh:gas.glsl>

uniform sampler2D VelSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec3 c = gasCell(GasCoarse, ivec2(gl_FragCoord.xy));
    if (c.y < 0 || !gasComputed(GasCoarse, c)) { fragColor = vec4(0.0); return; }
    vec3 xl = gasFetchWas(VelSampler, GasCoarse, c - ivec3(1, 0, 0)).xyz, xr = gasFetchWas(VelSampler, GasCoarse, c + ivec3(1, 0, 0)).xyz;
    vec3 yl = gasFetchWas(VelSampler, GasCoarse, c - ivec3(0, 1, 0)).xyz, yr = gasFetchWas(VelSampler, GasCoarse, c + ivec3(0, 1, 0)).xyz;
    vec3 zl = gasFetchWas(VelSampler, GasCoarse, c - ivec3(0, 0, 1)).xyz, zr = gasFetchWas(VelSampler, GasCoarse, c + ivec3(0, 0, 1)).xyz;
    vec3 w = vec3((yr.z - yl.z) - (zr.y - zl.y), (zr.x - zl.x) - (xr.z - xl.z), (xr.y - xl.y) - (yr.x - yl.x)) * 0.5 * GasStep.z;
    fragColor = vec4(w, length(w));
}
