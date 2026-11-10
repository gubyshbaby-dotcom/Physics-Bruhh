#version 330
#extension GL_ARB_separate_shader_objects : require

// Goo surface, fragment stage: a glossy liquid shaded per pixel.
//  - colour and opacity follow how much goo the eye looks through (thin films are pale and
//    see-through, deep goo is dark and nearly opaque);
//  - the sky mirrors in it at grazing angles, in the colour the sky has right now (the fog colour);
//  - two tight glints from the game's two light directions, a soft sheen around them, and light
//    glowing through thin edges;
//  - a slow shimmer of the surface, so it never looks frozen.
// The "block texture" look keeps the pixel texture of the honey / slime block as the body colour.

#include <minecraft:globals.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:light.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 gooParams;
layout(location = 3) in vec4 lightMapColor;
layout(location = 4) in vec2 texCoord0;
layout(location = 5) in vec3 gooPosition;
layout(location = 6) in vec3 gooNormal;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

// smooth value noise, 0..1
float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash13(i), hash13(i + vec3(1.0, 0.0, 0.0)), f.x), mix(hash13(i + vec3(0.0, 1.0, 0.0)), hash13(i + vec3(1.0, 1.0, 0.0)), f.x), f.y);
    float b = mix(mix(hash13(i + vec3(0.0, 0.0, 1.0)), hash13(i + vec3(1.0, 0.0, 1.0)), f.x), mix(hash13(i + vec3(0.0, 1.0, 1.0)), hash13(i + vec3(1.0, 1.0, 1.0)), f.x), f.y);
    return mix(a, b, f.z);
}

// what a mirror pointing along r would show: the sky in its current colour, darker ground below
vec3 skyColor(vec3 r) {
    vec3 horizon = FogColor.rgb;
    vec3 zenith = FogColor.rgb * vec3(0.55, 0.72, 1.12);
    vec3 ground = FogColor.rgb * vec3(0.34, 0.30, 0.26);
    return mix(ground, mix(horizon, zenith, smoothstep(0.0, 0.85, r.y)), smoothstep(-0.12, 0.04, r.y));
}

vec4 gooColor() {
    float material = step(0.5, gooParams.r);
    float clearLook = step(0.5, gooParams.g);
    float depth = gooParams.a * 2.6;

    vec3 view = normalize(-gooPosition);
    vec3 normal = normalize(gooNormal);
    float facing = dot(normal, view);
    // the far side of a closed surface: only the near side is drawn (threads have no depth and show both sides)
    if (facing < -0.08 && depth > 0.001) {
        discard;
    }
    if (facing < 0.0) {
        normal = -normal;
        facing = -facing;
    }

    // position in the world, wrapped so the numbers stay small; patterns below repeat every 256 blocks
    vec3 world = gooPosition - CameraOffset + vec3(CameraBlockPos & 255);
    float time = GameTime * 1200.0;

    // a slow shimmer: honey barely moves, slime quivers a little more
    vec3 flowAt = world * mix(5.0, 7.0, material) + vec3(0.0, time * mix(0.10, 0.35, material), time * 0.05);
    vec3 wobble = vec3(noise3(flowAt), noise3(flowAt + 17.3), noise3(flowAt + 41.7)) - 0.5;
    normal = normalize(normal + wobble * mix(0.10, 0.07, material));
    float nv = clamp(dot(normal, view), 0.0, 1.0);

    // how much goo the eye looks through here
    float thickness = min(depth / max(facing, 0.35), 2.5) + 0.03;
    vec3 absorb = mix(vec3(0.5, 2.5, 10.0), vec3(3.2, 0.45, 4.5), material);
    vec3 passes = exp(-absorb * thickness);
    float deep = smoothstep(0.1, 1.4, thickness);

    vec3 body;
    float alpha;
    if (clearLook > 0.5) {
        vec3 thin = mix(vec3(1.00, 0.76, 0.22), vec3(0.66, 0.96, 0.52), material);
        vec3 thick = mix(vec3(0.84, 0.36, 0.02), vec3(0.18, 0.58, 0.15), material);
        body = mix(thin, thick, deep);
        alpha = max(1.0 - dot(passes, vec3(0.30, 0.55, 0.15)), 0.30);
    } else {
        // the block's own pixels, projected along the three axes and blended by the way the surface faces
        vec3 w = pow(abs(normalize(gooNormal)), vec3(6.0));
        w /= w.x + w.y + w.z;
        vec3 f = fract(world);
        vec4 tex = texture(Sampler0, vec2(f.z, 1.0 - f.y)) * w.x + texture(Sampler0, vec2(f.x, f.z)) * w.y + texture(Sampler0, vec2(f.x, 1.0 - f.y)) * w.z;
        body = tex.rgb * mix(1.0, 0.82, deep);
        alpha = max(tex.a, mix(0.80, 0.72, material));
    }

    vec3 sun = normalize(Light0_Direction);
    vec3 fill = normalize(Light1_Direction);
    float diffuse = 0.60 + 0.25 * max(dot(normal, sun), 0.0) + 0.15 * max(dot(normal, fill), 0.0);
    // light coming through thin goo from behind makes its edges glow
    float through = (pow(max(dot(-view, sun), 0.0), 3.0) + 0.6 * pow(max(dot(-view, fill), 0.0), 3.0)) * (1.0 - deep) * clearLook;
    vec3 color = body * (diffuse + 0.55 * through) * lightMapColor.rgb;

    // the sky mirrored in the surface
    float fresnel = 0.04 + 0.80 * pow(1.0 - nv, 5.0);
    fresnel *= mix(0.55, 1.0, clearLook);
    color = mix(color, skyColor(reflect(-view, normal)) * lightMapColor.rgb, fresnel);
    alpha += (1.0 - alpha) * fresnel;

    // glints
    float glint = 0.0;
    float sheen = 0.0;
    float ndh = max(dot(normal, normalize(sun + view)), 0.0);
    glint += pow(ndh, 260.0);
    sheen += pow(ndh, 24.0);
    ndh = max(dot(normal, normalize(fill + view)), 0.0);
    glint += 0.6 * pow(ndh, 260.0);
    sheen += 0.6 * pow(ndh, 24.0);
    float shine = (glint * 2.4 + sheen * 0.10) * mix(0.7, 1.0, clearLook);
    color += shine * lightMapColor.rgb;
    alpha = min(alpha + shine, 0.985);

    return vec4(color, alpha) * ColorModulator;
}

void main() {
    vec4 color = gooColor();

#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
#endif
}
