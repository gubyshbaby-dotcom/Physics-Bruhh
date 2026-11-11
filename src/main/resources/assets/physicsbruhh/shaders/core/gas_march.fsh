#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, drawn: along the line of sight of each pixel, through the box, what the gas adds to the
// picture and how much of what is behind it still shows. Smoke scatters the light of the sun and
// the sky (less where other smoke stands in the light's way) and of the flames under it; hot gas
// glows. Blocks with nothing in them are stepped over whole. The result is light, not yet a colour
// of the screen (gas_show.fsh).

#include <minecraft:projection.glsl>
#include <physicsbruhh:gas.glsl>
#include <physicsbruhh:gas_view.glsl>
#include <physicsbruhh:gas_look.glsl>

uniform sampler2D SceneDepthSampler;
uniform sampler2D GasSampler;     // the fine grid, read smoothly
uniform sampler2D InfoSampler;    // gas_where.fsh
uniform sampler2D LightSampler;   // the map of the world, read smoothly: red the sky's light, alpha the light of lamps

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Steps along the ray, in fine cells.
const float STRIDE = 0.5;

vec4 gasHere(vec3 blocks, float fpb) {
    return gasValue(gasAt(GasSampler, GasFine, blocks * fpb));
}

float soulShare(vec4 v) {
    return clamp(v.w / (v.y + v.z + 1.0e-4), 0.0, 1.0);
}

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

    // where the line of sight is inside the box
    vec3 t0 = -eye * inv, t1 = (GasBlocks.xyz - eye) * inv;
    vec3 lo = min(t0, t1), hi = max(t0, t1);
    float enter = max(max(lo.x, lo.y), max(lo.z, 0.0));
    float leave = min(min(hi.x, hi.y), min(hi.z, sceneFar));
    if (enter >= leave) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

    float fpb = GasStep.z * GasStep.w;
    float ds = STRIDE / fpb;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    // where along the ray the first place looked at lies: different for neighbouring pixels in an even pattern, and from frame to frame
    float jitter = fract(52.9829189 * fract(0.06711056 * float(pixel.x) + 0.00583715 * float(pixel.y)) + 0.61803399 * GasSun.w);
    vec3 sun = GasSun.xyz;
    float towards = gasPhase(dot(dir, sun));

    vec3 light = vec3(0.0);
    float clear = 1.0;
    float t = enter + 1.0e-3;
    for (int blocks = 0; blocks < 200 && t < leave && clear > 0.01; blocks++) {
        vec3 pos = eye + dir * t;
        vec3 corner = floor(pos);
        vec3 edge = (corner + step(vec3(0.0), dir) - pos) * inv;
        float end = min(t + min(edge.x, min(edge.y, edge.z)) + 1.0e-3, leave);
        vec4 info = texelFetch(InfoSampler, gasPixel(GasBlocks, clamp(ivec3(corner), ivec3(0), ivec3(GasBlocks.xyz) - 1)), 0);
        if (info.r <= 0.0 && info.g <= 0.0) { t = end; continue; }
        // the places looked at lie the same distance apart all along the ray, whatever blocks were stepped over
        float at = enter + (ceil((t - enter) / ds - jitter) + jitter) * ds;
        for (int i = 0; i < 64 && at < end && clear > 0.01; i++, at += ds) {
            vec3 p = eye + dir * at;
            vec4 v = gasHere(p, fpb);
            if (v.x < 0.002 && v.y < 0.12) continue;
            // a flame's edge is not the smooth one the grid gives it: it wavers
            if (info.g > 0.25) {
                vec2 sway = gasSway(p, GasSunLight.a) * (GAS_SWAY * smoothstep(0.1, 0.5, v.y + 0.3 * info.g));
                v = gasHere(p + vec3(sway.x, 0.0, sway.y), fpb);
            }
            float heat = v.y;
            // what the grid is too coarse for: a flame is not a smooth lump but tongues and gaps, smoke is not a mist but curls
            float lumps = gasDetail(p, heat, GasSunLight.a, 2.3, 6.1);
            if (heat > 0.12) heat *= 1.0 + GAS_FLAME_DETAIL * (lumps - 0.5) * smoothstep(0.12, 0.45, heat);
            // hot gas is thin gas: as many times fewer specks of smoke to the block as it is hotter than the air;
            // and inside a flame what will be smoke is still glowing soot, seen by its own light and not by the sun's
            float smoke = v.x / (1.0 + 5.0 * heat) * (1.0 - smoothstep(0.3, 0.75, heat)) * clamp(1.0 + GAS_SMOKE_DETAIL * (2.0 * lumps - 1.0), 0.0, 2.0);
            vec3 lit = vec3(0.0);
            if (smoke > 0.002) {
                // how much smoke the sun's light crosses on its way here; light scattered more than once gets further than that
                float depth = 0.0;
                depth += gasHere(p + sun * 0.075, fpb).x * 0.15;
                depth += gasHere(p + sun * 0.3, fpb).x * 0.3;
                depth += gasHere(p + sun * 0.725, fpb).x * 0.55;
                depth += gasHere(p + sun * 1.45, fpb).x * 0.9;
                depth += gasHere(p + sun * 2.6, fpb).x * 1.4;
                depth *= 1.6 * GAS_SMOKE_DIMS;
                float direct = exp(-depth);
                float soft = 0.75 * direct + 0.25 * exp(-0.22 * depth);
                // the flames under the smoke light it from below
                vec4 u = gasHere(p - vec3(0.0, 0.35, 0.0), fpb);
                vec3 fire = gasFlame(u.y, soulShare(u)) * 0.5;
                u = gasHere(p - vec3(0.0, 0.9, 0.0), fpb);
                fire += gasFlame(u.y, soulShare(u)) * 0.3;
                u = gasHere(p - vec3(0.0, 1.8, 0.0), fpb);
                fire += gasFlame(u.y, soulShare(u)) * 0.2;
                // the light of the place: how much of the sky is seen from it, and the lamps and fires near it
                vec4 around = gasAt(LightSampler, GasBlocks, p);
                lit = around.r * (GasSunLight.rgb * (towards * direct + 0.6 * soft) + GasSkyLight.rgb * (0.22 + 0.78 * soft))
                    + vec3(1.0, 0.82, 0.6) * (0.3 * around.a * around.a) + 0.2 * fire;
            }
            light += clear * (lit * (GAS_SMOKE_WHITE * (1.0 - exp(-GAS_SMOKE_DIMS * smoke * ds))) + gasFlame(heat, soulShare(v)) * ds);
            clear *= exp(-(GAS_SMOKE_DIMS * smoke + GAS_FLAME_DIMS * smoothstep(0.3, 0.9, heat)) * ds);
        }
        t = end;
    }
    fragColor = vec4(light, clear);
}
