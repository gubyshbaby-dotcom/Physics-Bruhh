#version 330
#extension GL_ARB_separate_shader_objects : require

// Liquid look, last step: puts the goo into the picture. For every pixel that has goo in front of
// the scene it works out the surface (from the smoothed nearest-surface texture), bends the line of
// sight through it and takes the scene from there, lets the goo swallow light by its thickness
// (honey: blue first, then green; slime: red and blue), adds the light scattered inside it, mirrors
// the sky by the angle of view and puts the sun's glint on top.

#include <minecraft:projection.glsl>
#include <physicsbruhh:goo_frame.glsl>

uniform sampler2D SceneSampler;        // the frame so far (a copy of it)
uniform sampler2D SceneDepthSampler;   // and its depth
uniform sampler2D FluidSampler;        // nearest goo surface per pixel, smoothed: r minus its distance along the view axis, g how much "goo here"
uniform sampler2D ThickSampler;        // r: honey along the line of sight, g: slime, b / a: the same weighted by block / sky light

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// how fast light of each colour dies away per block of goo
const vec3 HONEY_ABSORB = vec3(1.0, 4.4, 16.0);
const vec3 SLIME_ABSORB = vec3(5.0, 0.75, 6.2);
// and how soon the goo's own colour takes over from what is behind it
const float HONEY_CLOUD = 4.6;
const float SLIME_CLOUD = 3.8;
// how much of the smoothed "goo here" makes the outline
const float OUTLINE_LEVEL = 0.74;
// the least goo a line of sight through it meets, in blocks
const float DROP = 0.085;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x), mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x), f.y);
}

// The point of a pixel's line of sight that lies `along` blocks down the view axis, in camera space.
// (Two points of the line are taken back through the projection; that is right whatever the
// projection has been multiplied with, view bobbing included.)
vec3 viewAt(mat4 unproject, vec2 ndc, float along) {
    vec4 a = unproject * vec4(ndc, 1.0, 1.0);
    vec4 b = unproject * vec4(ndc, 0.5, 1.0);
    vec3 pa = a.xyz / a.w, pb = b.xyz / b.w;
    return pa + (pb - pa) * ((-along - pa.z) / (pb.z - pa.z));
}

// How far down the view axis the scene is at a place on the screen.
float sceneAlong(mat4 unproject, vec2 uv) {
    float z = texture(SceneDepthSampler, uv).r;
    if (GooSun.w < 0.5) {
        z = z * 2.0 - 1.0;
    }
    vec4 p = unproject * vec4(uv * 2.0 - 1.0, z, 1.0);
    return -p.z / p.w;
}

// What the goo mirrors: the game's sky colours, a few soft clouds, the glow around the sun.
// Everything here is linear light.
vec3 skyLight(vec3 dir, vec3 sun) {
    vec3 horizon = pow(GooHorizon.rgb, vec3(2.2)), zenith = pow(GooSky.rgb, vec3(2.2));
    float day = GooHorizon.a;
    if (dir.y < 0.0) {
        // below the horizon: the ground, dim and a little warm
        return mix(horizon, horizon * vec3(0.30, 0.24, 0.18), smoothstep(0.0, -0.25, dir.y));
    }
    vec3 c = mix(horizon, zenith, smoothstep(0.0, 0.55, dir.y));
    vec2 p = dir.xz / (dir.y + 0.10) * 1.15 + vec2(GooSky.a * 0.010, GooSky.a * 0.004);
    float n = 0.5 * vnoise(p) + 0.25 * vnoise(p * 2.03 + vec2(17.1, 9.2)) + 0.125 * vnoise(p * 4.12 + vec2(51.7, 27.6));
    c = mix(c, vec3(0.95, 0.96, 1.0) * (0.25 + 0.75 * day), smoothstep(0.47, 0.66, n) * smoothstep(0.01, 0.15, dir.y) * 0.9);
    float towards = max(dot(dir, sun), 0.0);
    c += GooLight.rgb * (pow(towards, 6.0) * 0.10 + pow(towards, 48.0) * 0.28 + smoothstep(0.9990, 0.9996, towards) * 5.0);
    return c;
}

void main() {
    ivec2 here = ivec2(gl_FragCoord.xy);
    vec2 surface = texelFetch(FluidSampler, here, 0).rg;
    float along = -surface.r;
    if (along > GOO_NONE) {
        discard;
    }
    ivec2 last = textureSize(FluidSampler, 0) - 1;

    // neighbours, for the slope of the surface
    float left = -texelFetch(FluidSampler, max(here - ivec2(1, 0), ivec2(0)), 0).r;
    float right = -texelFetch(FluidSampler, min(here + ivec2(1, 0), last), 0).r;
    float below = -texelFetch(FluidSampler, max(here - ivec2(0, 1), ivec2(0)), 0).r;
    float above = -texelFetch(FluidSampler, min(here + ivec2(0, 1), last), 0).r;

    // the outline: where the smoothed "goo here" falls below a level; one pixel soft
    float present = surface.g;
    vec2 outward = -vec2(dFdx(present), dFdy(present));
    float steep = max(abs(outward.x) + abs(outward.y), 1.0e-5);
    float cover = clamp((present - OUTLINE_LEVEL) / steep + 0.5, 0.0, 1.0);

    mat4 unproject = inverse(ProjMat);
    vec2 ndc = texCoord * 2.0 - 1.0;
    // one pixel to the right / one row on, in clip space (rows may run up or down the screen)
    vec2 pixelStep = vec2(dFdx(ndc.x), dFdy(ndc.y));
    float sceneHere = sceneAlong(unproject, texCoord);
    cover *= 1.0 - smoothstep(sceneHere + 0.01, sceneHere + 0.05, along);

    // the surface: its place and which way it faces (camera space)
    vec3 P = viewAt(unproject, ndc, along);
    vec3 dx = vec3(1.0, 0.0, 0.0), dy = vec3(0.0, 1.0, 0.0);
    if (min(left, right) < GOO_NONE) {
        dx = abs(right - along) < abs(along - left)
            ? viewAt(unproject, ndc + vec2(pixelStep.x, 0.0), right) - P
            : P - viewAt(unproject, ndc - vec2(pixelStep.x, 0.0), left);
    }
    if (min(below, above) < GOO_NONE) {
        dy = abs(above - along) < abs(along - below)
            ? viewAt(unproject, ndc + vec2(0.0, pixelStep.y), above) - P
            : P - viewAt(unproject, ndc - vec2(0.0, pixelStep.y), below);
    }
    vec3 n = normalize(cross(dx, dy));
    if (n.z < 0.0) {
        n = -n;
    }
    // towards its outline the goo curves away, like the rim of a drop
    float rim = 1.0 - smoothstep(OUTLINE_LEVEL, 0.985, present);
    if (rim > 0.0) {
        n = normalize(n + vec3(outward * sign(pixelStep) / steep * 1.4 * rim * rim, 0.0));
    }
    vec3 V = normalize(-P);

    // how much goo the line of sight goes through
    vec4 amount = texture(ThickSampler, texCoord);
    float honey = amount.r, slime = amount.g, thick = honey + slime;
    float slimeShare = slime / max(thick, 1.0e-5);
    // goo is never thinner than a drop of it: the splatted amounts thin out towards the outline
    float lift = max(DROP - thick, 0.0);
    honey += lift * (1.0 - slimeShare);
    slime += lift * slimeShare;
    float amountSeen = thick;
    thick += lift;
    cover *= smoothstep(0.004, 0.008, amountSeen);
    if (cover < 0.004) {
        discard;
    }
    float blockLight = clamp(amount.b / max(amountSeen, 1.0e-5), 0.0, 1.0);
    float skyShare = clamp(amount.a / max(amountSeen, 1.0e-5), 0.0, 1.0);

    // the scene behind, seen through the bent surface
    float bend = 0.22 * min(thick, 0.7) + 0.024;
    vec4 bent = ProjMat * vec4(P.xy - n.xy * bend, P.z, 1.0);
    vec2 from = clamp(bent.xy / bent.w * 0.5 + 0.5, vec2(0.002), vec2(0.998));
    if (sceneAlong(unproject, from) < along - 0.06) {
        from = texCoord;   // that is something in front of the goo: it cannot be seen through it
    }
    vec3 behind = pow(texture(SceneSampler, from).rgb, vec3(2.2));

    // light swallowed on the way through, light scattered inside
    float film = 0.05 * smoothstep(0.010, 0.06, thick);   // even the thinnest film has its colour
    vec3 through = exp(-(HONEY_ABSORB * (honey + film * (1.0 - slimeShare)) + SLIME_ABSORB * (slime + film * slimeShare)));
    mat3 toWorld = transpose(mat3(GooView));
    vec3 nW = toWorld * n, VW = toWorld * V;
    vec3 sun = GooSun.xyz;
    float facing = max(dot(nW, sun), 0.0);
    float shining = pow(max(dot(-VW, sun), 0.0), 3.0);   // looking towards the sun through the goo
    vec3 skyAmbient = pow(GooSky.rgb, vec3(2.2)) * 0.35 + vec3(0.03);
    vec3 lamp = vec3(1.0, 0.72, 0.42) * blockLight * blockLight * 1.1;
    vec3 light = skyShare * (GooLight.rgb * (0.42 + 0.40 * facing + 0.55 * shining) + skyAmbient) + lamp + vec3(0.012);
    vec3 honeyGlow = mix(vec3(1.0, 0.63, 0.09), vec3(0.95, 0.40, 0.025), smoothstep(0.15, 1.0, thick)) * (1.0 - exp(-thick * HONEY_CLOUD));
    vec3 slimeGlow = mix(vec3(0.42, 0.90, 0.24), vec3(0.16, 0.62, 0.11), smoothstep(0.15, 1.0, thick)) * (1.0 - exp(-thick * SLIME_CLOUD));
    vec3 colour = behind * through + mix(honeyGlow, slimeGlow, slimeShare) * light * 0.92;

    // the sky in the surface, more of it at a glancing angle; the sun's glint
    vec3 mirrored = skyLight(reflect(-VW, nW), sun) * max(skyShare, 0.04) + lamp * 0.15;
    float nv = max(dot(n, V), 0.0);
    float fresnel = (0.05 + 0.90 * pow(1.0 - nv, 5.0)) * (0.3 + 0.7 * smoothstep(0.02, 0.10, thick));
    colour = mix(colour, mirrored, clamp(fresnel, 0.0, 1.0));
    float ndh = max(dot(nW, normalize(sun + VW)), 0.0);
    colour += GooLight.rgb * skyShare * (pow(ndh, 1400.0) * 4.0 + pow(ndh, 180.0) * 0.30);

    fragColor = vec4(pow(clamp(colour, 0.0, 1.0), vec3(1.0 / 2.2)), cover);
}
