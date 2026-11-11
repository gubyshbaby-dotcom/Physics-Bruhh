#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, step 2: the velocity carried along by itself, then pushed: hot gas up, smoke-laden gas a
// little down, small whirls kept alive, unrest over a flame, creatures and explosions dragging it.

#include <physicsbruhh:gas.glsl>

uniform sampler2D VelSampler;     // velocity after the last step
uniform sampler2D SpinSampler;    // step 1
uniform sampler2D GasSampler;     // smoke, heat, fuel after the last step
uniform sampler2D WorldSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec3 c = gasCell(GasCoarse, ivec2(gl_FragCoord.xy));
    if (c.y < 0 || !gasComputed(GasCoarse, c)) { fragColor = vec4(0.0); return; }
    int cpb = int(GasStep.z);
    vec4 block = gasBlock(WorldSampler, c / cpb);
    if (block.r > 0.5) { fragColor = vec4(0.0); return; }
    float dt = GasStep.x;
    vec3 p = vec3(c) + 0.5;

    // carried along by itself
    vec3 u = gasFetchWas(VelSampler, GasCoarse, c).xyz;
    vec3 a = gasAtWas(VelSampler, GasCoarse, p - dt * u * GasStep.z).xyz * exp(-GasC.y * dt);

    // hot gas up, smoke down
    vec4 g = gasAtWas(GasSampler, GasFine, p * GasStep.w);
    a.y += dt * (GasA.x * g.g - GasA.y * g.r);

    // small whirls kept alive
    if (all(greaterThan(c, gasLow(GasCoarse))) && all(lessThan(c, gasHigh(GasCoarse)))) {
        vec3 grad = vec3(gasFetchNear(SpinSampler, GasCoarse, c + ivec3(1, 0, 0)).w - gasFetchNear(SpinSampler, GasCoarse, c - ivec3(1, 0, 0)).w,
                         gasFetchNear(SpinSampler, GasCoarse, c + ivec3(0, 1, 0)).w - gasFetchNear(SpinSampler, GasCoarse, c - ivec3(0, 1, 0)).w,
                         gasFetchNear(SpinSampler, GasCoarse, c + ivec3(0, 0, 1)).w - gasFetchNear(SpinSampler, GasCoarse, c - ivec3(0, 0, 1)).w);
        float len = length(grad);
        if (len > 1.0e-5) {
            a += (dt * GasC.z / (GasStep.z * len)) * cross(grad, gasFetchNear(SpinSampler, GasCoarse, c).xyz);
        }
    }

    // unrest over a source
    if (block.g > 0.5) {
        float much = dt * GasD.x * block.b;
        a.x += much * (gasFlicker(uint(c.x), uint(c.y), uint(c.z), GasStep.y, 7.0) - 0.5);
        a.z += much * (gasFlicker(uint(c.z), uint(c.y), uint(c.x) + 977u, GasStep.y, 7.0) - 0.5);
    }

    // creatures and explosions drag the gas with them
    int blobs = int(GasMoved.w);
    vec3 place = p / GasStep.z;
    for (int i = 0; i < blobs; i++) {
        vec4 ball = GasBlob[i * 3];
        vec3 d = place - ball.xyz;
        float d2 = dot(d, d);
        if (d2 < ball.w * ball.w) {
            a += (GasBlob[i * 3 + 2].xyz - a) * ((1.0 - d2 / (ball.w * ball.w)) * (1.0 - exp(-8.0 * dt)));
        }
    }
    fragColor = vec4(a, 0.0);
}
