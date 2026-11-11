#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, step 3: how much the velocity piles gas up or thins it out in each coarse cell, less what
// burning and explosions add. Red carries the pressure to start the solve from (last step's),
// green what has to be undone; a cell inside a block is marked in red.

#include <physicsbruhh:gas.glsl>

uniform sampler2D VelSampler;        // step 2
uniform sampler2D GasSampler;        // smoke, heat, fuel after the last step
uniform sampler2D PressureSampler;   // pressure after the last step
uniform sampler2D WorldSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec3 c = gasCell(GasCoarse, ivec2(gl_FragCoord.xy));
    if (c.y < 0) { fragColor = vec4(0.0); return; }
    int cpb = int(GasStep.z);
    if (gasBlock(WorldSampler, c / cpb).r > 0.5) { fragColor = vec4(GAS_WALL, 0.0, 0.0, 0.0); return; }
    float dt = GasStep.x;
    ivec3 moved = ivec3(GasMoved.xyz) * cpb;

    // a neighbour inside a block does not move (its velocity is kept at zero); beyond the edge of the box the gas moves as it does here
    float d = (gasFetch(VelSampler, GasCoarse, c + ivec3(1, 0, 0)).x - gasFetch(VelSampler, GasCoarse, c - ivec3(1, 0, 0)).x
             + gasFetch(VelSampler, GasCoarse, c + ivec3(0, 1, 0)).y - gasFetch(VelSampler, GasCoarse, c - ivec3(0, 1, 0)).y
             + gasFetch(VelSampler, GasCoarse, c + ivec3(0, 0, 1)).z - gasFetch(VelSampler, GasCoarse, c - ivec3(0, 0, 1)).z) * 0.5 * GasStep.z;

    // gas that burns takes more room; an explosion makes a lot of gas at once
    vec3 p = vec3(c) + 0.5;
    float made = 0.0;
    if (GasC.w > 0.0) {
        vec4 g = gasAt(GasSampler, GasFine, (p + vec3(moved)) * GasStep.w);
        if (g.g > GasB.x) made += GasC.w * g.b * (1.0 - exp(-GasB.y * dt)) / dt;
    }
    int blobs = int(GasMoved.w);
    vec3 place = p / GasStep.z;
    for (int i = 0; i < blobs; i++) {
        float expand = GasBlob[i * 3 + 2].w;
        if (expand == 0.0) continue;
        vec4 ball = GasBlob[i * 3];
        vec3 off = place - ball.xyz;
        float d2 = dot(off, off);
        if (d2 < ball.w * ball.w) made += expand * (1.0 - d2 / (ball.w * ball.w));
    }

    float before = 0.0;
    if (gasInside(GasCoarse, c + moved)) {
        before = texelFetch(PressureSampler, gasPixel(GasCoarse, c + moved), 0).r;
        if (before < GAS_WALL_TEST) before = 0.0;
    }
    fragColor = vec4(before, d - made, 0.0, 0.0);
}
