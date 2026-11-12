#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, step 6, first half: smoke, heat and fuel carried along by the velocity. Each fine cell takes
// what was, a step ago, where its gas has come from.

#include <physicsbruhh:gas.glsl>
#include <physicsbruhh:gas_carry.glsl>

uniform sampler2D GasSampler;     // smoke, heat, fuel after the last step
uniform sampler2D VelSampler;     // step 5
uniform sampler2D WorldSampler;
uniform sampler2D InfoSampler;    // what each block held after the last step (gas_where.fsh)

layout(location = 0) out vec4 fragColor;

#ifdef GAS_KEPT
// (see gas_blocks.vsh) a block that is not awake holds nothing, whatever this texture held in it before
layout(location = 0) flat in float gasAsleep;
#endif

void main() {
#ifdef GAS_KEPT
    if (gasAsleep > 0.5) { fragColor = vec4(0.0); return; }
#endif
    ivec3 c = gasCell(GasFine, ivec2(gl_FragCoord.xy));
    int fpb = int(GasStep.z * GasStep.w);
    if (c.y < 0 || !gasComputed(GasFine, c)) { fragColor = vec4(0.0); return; }
    vec4 block = gasBlock(WorldSampler, c / fpb);
    if (block.r > 0.5 || !gasAwake(InfoSampler, block, c / fpb, (vec3(c) + 0.5) / float(fpb), 0.0)) { fragColor = vec4(0.0); return; }
    fragColor = gasAtWas(GasSampler, GasFine, gasCameFrom(VelSampler, WorldSampler, c));
}
