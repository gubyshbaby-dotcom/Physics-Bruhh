#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, drawn, the shimmer: how much hot gas lies along the line of sight of each pixel (heat times
// blocks). Hot air bends light less than cold air, so what is seen through a flame and the air
// over it wavers; gas_show.fsh shifts the picture behind by how this number changes from pixel to
// pixel. Coarse on purpose: a quarter of the screen's size, long steps.

#include <minecraft:projection.glsl>
#include <physicsbruhh:gas.glsl>
#include <physicsbruhh:gas_view.glsl>
#include <physicsbruhh:gas_look.glsl>

uniform sampler2D SceneDepthSampler;
uniform sampler2D GasSampler;     // the fine grid, read smoothly
uniform sampler2D InfoSampler;    // gas_where.fsh

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    mat4 unproject = inverse(ProjMat);
    float z = texture(SceneDepthSampler, texCoord).r;
    if (GasEye.w < 0.5) z = z * 2.0 - 1.0;
    vec4 seen = unproject * vec4(texCoord * 2.0 - 1.0, z, 1.0);
    vec3 scene = seen.xyz / seen.w;
    float sceneFar = length(scene);
    vec3 dir = transpose(mat3(GasViewMat)) * (scene / sceneFar);
    dir += vec3(equal(dir, vec3(0.0))) * 1.0e-6;
    vec3 inv = 1.0 / dir;
    vec3 eye = GasEye.xyz;
    vec3 t0 = -eye * inv, t1 = (GasBlocks.xyz - eye) * inv;
    vec3 lo = min(t0, t1), hi = max(t0, t1);
    float enter = max(max(lo.x, lo.y), max(lo.z, 0.0));
    float leave = min(min(hi.x, hi.y), min(hi.z, sceneFar));
    float column = 0.0;
    if (enter < leave) {
        float fpb = GasStep.z * GasStep.w;
        float ds = 0.3;
        float t = enter + ds * fract(52.9829189 * fract(0.06711056 * gl_FragCoord.x + 0.00583715 * gl_FragCoord.y));
        for (int i = 0; i < 160 && t < leave; i++, t += ds) {
            vec3 p = eye + dir * t;
            vec4 info = texelFetch(InfoSampler, gasPixel(GasBlocks, clamp(ivec3(floor(p)), ivec3(0), ivec3(GasBlocks.xyz) - 1)), 0);
            if (info.r <= 0.0 && info.g <= 0.0) continue;
            column += max(gasValue(gasAt(GasSampler, GasFine, p * fpb)).y - 0.03, 0.0) * ds;
        }
    }
    fragColor = vec4(column, 0.0, 0.0, 1.0);
}
