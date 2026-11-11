#version 330
#extension GL_ARB_separate_shader_objects : require

// Liquid look, last step: puts the goo into the picture. For every pixel that has goo in front of
// the scene it works out the surface (from the smoothed nearest-surface texture), bends the line of
// sight through it and takes the scene from there, lets the goo swallow light by its thickness
// (honey: blue first, then green; slime: red and blue), adds the light scattered inside it, mirrors
// the sky by the angle of view and puts the sun's glint on top. Everywhere else it only lays the
// goo's shade on the world: what lies under goo gets less light, and light of the goo's colour.

#include <minecraft:projection.glsl>
#include <physicsbruhh:goo_frame.glsl>

uniform sampler2D SceneSampler;        // the frame so far (a copy of it)
uniform sampler2D SceneDepthSampler;   // and its depth
uniform sampler2D FluidSampler;        // nearest goo surface per pixel, smoothed: r minus its distance along the view axis, g how much "goo here"
uniform sampler2D ThickSampler;        // r: honey along the line of sight, g: slime, b / a: the same weighted by block / sky light
uniform sampler2D ShadeSampler;        // seen from above: r honey, g slime hanging over each spot of the ground, a: minus the height of the lowest of it

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Honey is a clear liquid: it hardly scatters light, it swallows it, blue first, then green, next
// to no red (per block of honey). A film of it is pale gold, a finger's width amber, a block deep orange.
const vec3 HONEY_ABSORB = vec3(0.26, 1.1, 6.5);
// the little that is scattered on the way and comes back out warm (per block)
const float HONEY_CLOUD = 2.2;
// Slime is cloudy: light that goes in is thrown about and comes out green, and a finger's width of
// it hides what is behind. How soon it does that (per block), and what it does to light that passes straight through:
const float SLIME_CLOUD = 9.0;
const vec3 SLIME_ABSORB = vec3(4.5, 0.5, 6.0);
// how much of the smoothed "goo here" makes the outline
const float OUTLINE_LEVEL = 0.74;
// the least goo a line of sight through it meets, in blocks
const float DROP = 0.085;
// how much of the light under goo comes through the goo rather than from all around
const float SHADE = 0.78;

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
    c += GooLight.rgb * (pow(towards, 6.0) * 0.10 + pow(towards, 48.0) * 0.22);
    return c;
}

// How much light reaches a place in the world (camera space) past the goo hanging over it.
vec3 shadeAt(vec3 P) {
    vec3 place = transpose(mat3(GooView)) * P - GooOffset.xyz;
    vec2 uv = (place.xz - GooShade.xy) * GooShade.zw;
    if (!(uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0)) {
        return vec3(1.0);
    }
    vec4 over = texture(ShadeSampler, uv);
    float above = -over.a - place.y;   // how far over this place the lowest goo is
    if (!(above > -0.12)) {
        return vec3(1.0);
    }
    // the further up the goo, the more light gets in from the sides
    float reach = 1.0 / (1.0 + 0.6 * max(above, 0.0));
    // through honey the light comes out golden; slime mostly keeps it
    vec3 tint = exp(-(HONEY_ABSORB * over.r * 2.0 + SLIME_ABSORB * over.g * 0.6));
    float dark = 1.0 - 0.30 * (1.0 - exp(-over.r * 6.0)) - 0.62 * (1.0 - exp(-over.g * SLIME_CLOUD));
    return mix(vec3(1.0), tint * max(dark, 0.2), SHADE * reach);
}

void main() {
    ivec2 here = ivec2(gl_FragCoord.xy);
    vec2 surface = texelFetch(FluidSampler, here, 0).rg;
    float along = -surface.r;
    mat4 unproject = inverse(ProjMat);
    vec2 ndc = texCoord * 2.0 - 1.0;
    float sceneHere = sceneAlong(unproject, texCoord);

    // the outline: where the smoothed "goo here" falls below a level; one pixel soft
    float present = surface.g;
    vec2 outward = -vec2(dFdx(present), dFdy(present));
    float steep = max(abs(outward.x) + abs(outward.y), 1.0e-5);
    float cover = along > GOO_NONE ? 0.0 : clamp((present - OUTLINE_LEVEL) / steep + 0.5, 0.0, 1.0);
    cover *= 1.0 - smoothstep(sceneHere + 0.01, sceneHere + 0.05, along);
    vec4 amount = texture(ThickSampler, texCoord);
    cover *= smoothstep(0.004, 0.008, amount.r + amount.g);

#ifdef GOO_DEBUG
    // what the passes before this one came up with, as colours: red the amount of goo along the line of sight (blocks),
    // green the same times ten, blue how far the goo's surface is in front of the world behind it (half a block per unit)
    if (along > GOO_NONE) {
        discard;
    }
    fragColor = vec4(clamp(amount.r + amount.g, 0.0, 1.0), clamp((amount.r + amount.g) * 10.0, 0.0, 1.0), clamp((sceneHere - along) * 0.5, 0.0, 1.0), 1.0);
    return;
#endif
    // the world as it was, in the goo's shade where there is goo over it
    vec3 shaded = pow(texture(SceneSampler, texCoord).rgb, vec3(2.2));
    vec3 shade = sceneHere < 2000.0 ? shadeAt(viewAt(unproject, ndc, sceneHere)) : vec3(1.0);
    if (cover < 0.004) {
        if (min(shade.r, min(shade.g, shade.b)) > 0.995) {
            discard;
        }
        fragColor = vec4(pow(shaded * shade, vec3(1.0 / 2.2)), 1.0);
        return;
    }

    ivec2 last = textureSize(FluidSampler, 0) - 1;
    // neighbours, for the slope of the surface
    float left = -texelFetch(FluidSampler, max(here - ivec2(1, 0), ivec2(0)), 0).r;
    float right = -texelFetch(FluidSampler, min(here + ivec2(1, 0), last), 0).r;
    float below = -texelFetch(FluidSampler, max(here - ivec2(0, 1), ivec2(0)), 0).r;
    float above = -texelFetch(FluidSampler, min(here + ivec2(0, 1), last), 0).r;
    // one pixel to the right / one row on, in clip space (rows may run up or down the screen)
    vec2 pixelStep = vec2(dFdx(ndc.x), dFdy(ndc.y));

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
    float honey = amount.r, slime = amount.g, thick = honey + slime;
    float slimeShare = slime / max(thick, 1.0e-5);
    // goo is never thinner than a drop of it: the splatted amounts thin out towards the outline
    float lift = max(DROP - thick, 0.0);
    honey += lift * (1.0 - slimeShare);
    slime += lift * slimeShare;
    float amountSeen = thick;
    thick += lift;
    float blockLight = clamp(amount.b / max(amountSeen, 1.0e-5), 0.0, 1.0);
    float skyShare = clamp(amount.a / max(amountSeen, 1.0e-5), 0.0, 1.0);

    // the scene behind, seen through the bent surface
    float bend = 0.22 * min(thick, 0.7) + 0.024;
    vec4 bent = ProjMat * vec4(P.xy - n.xy * bend, P.z, 1.0);
    vec2 from = clamp(bent.xy / bent.w * 0.5 + 0.5, vec2(0.002), vec2(0.998));
    if (sceneAlong(unproject, from) < along - 0.06) {
        from = texCoord;   // that is something in front of the goo: it cannot be seen through it
    }
    float sceneFrom = sceneAlong(unproject, from);
    vec3 behind = pow(texture(SceneSampler, from).rgb, vec3(2.2));
    // what is seen through the goo lies in its shade too (less so: the way back to the eye is counted below)
    if (sceneFrom < 2000.0) {
        behind *= mix(vec3(1.0), shadeAt(viewAt(unproject, from * 2.0 - 1.0, sceneFrom)), 0.45);
    }

    mat3 toWorld = transpose(mat3(GooView));
    vec3 nW = toWorld * n, VW = toWorld * V;
    vec3 sun = GooSun.xyz;
    float nv = max(dot(n, V), 0.0);

    // Light swallowed on the way through. Where the surface is seen at a slant (and at the rim, where
    // it curves away) the line of sight is bent sideways and has further to go: that is the dark,
    // saturated outline every drop of honey has.
    float slant = (1.0 - nv) * (1.0 - nv);
    // (a finger's width of honey already has its full amber: a thread or a thin stream of it is not pale)
    float honeyWay = honey + 0.30 * (1.0 - exp(-honey * 14.0)) + (1.0 - slimeShare) * 0.30 * slant;
    vec3 through = exp(-(HONEY_ABSORB * honeyWay + SLIME_ABSORB * (slime + slimeShare * (0.02 + 0.10 * slant))));
    // just inside the rim the curved edge gathers light into a brighter band
    float band = smoothstep(0.10, 0.40, rim) * (1.0 - smoothstep(0.45, 0.85, rim));
    behind *= 1.0 + 0.45 * band * (1.0 - slimeShare);

    float around = 0.5 + 0.5 * dot(nW, sun);              // soft light all round a cloudy body
    float shining = pow(max(dot(-VW, sun), 0.0), 3.0);    // looking towards the sun through the goo
    vec3 lamp = vec3(1.0, 0.72, 0.42) * blockLight * blockLight * 1.1;
    vec3 sunLight = GooLight.rgb * skyShare;
    vec3 skyAndLamps = skyShare * (pow(GooSky.rgb, vec3(2.2)) * 0.45 + vec3(0.04)) + lamp + vec3(0.012);

    // honey: clear; only a little warm light comes back out of it, more when the sun stands behind it
    vec3 honeyGlow = vec3(1.0, 0.60, 0.06) * (1.0 - exp(-honey * HONEY_CLOUD)) * (sunLight * (0.34 + 0.55 * shining) + skyAndLamps * 0.5);
    // slime: a cloudy body of its own colour, pale and letting light through where thin, deep where thick
    float clouded = (1.0 - exp(-slime * SLIME_CLOUD)) * slimeShare;
    vec3 slimeBody = mix(vec3(0.40, 0.86, 0.18), vec3(0.07, 0.44, 0.035), smoothstep(0.08, 0.75, slime));
    vec3 slimeLit = slimeBody * (sunLight * (0.10 + 0.74 * around * around + 0.35 * shining * exp(-slime * 2.5)) + skyAndLamps * 0.9);
    vec3 colour = behind * through * (1.0 - clouded) + honeyGlow * (1.0 - slimeShare) + slimeLit * clouded;

    // the surface as a mirror: little seen straight on, everything at a glancing angle. It shows the sky,
    // or the world behind the goo where the mirrored line of sight runs on into it. And the sun's glint, small and sharp.
    vec3 bounce = reflect(-V, n);
    vec3 mirrored = skyLight(toWorld * bounce, sun) * max(skyShare, 0.04) + lamp * 0.15;
    if (bounce.z < 0.0) {
        // the mirrored line of sight goes on into the picture: what it meets there is on the screen already
        vec4 on = ProjMat * vec4(P + bounce * 0.6, 1.0);
        vec2 at = on.xy / on.w * 0.5 + 0.5;
        if (on.w > 0.0 && at.x > 0.002 && at.x < 0.998 && at.y > 0.002 && at.y < 0.998) {
            float there = sceneAlong(unproject, at);
            if (there > along && there < 2000.0) {
                mirrored = mix(mirrored, pow(texture(SceneSampler, at).rgb, vec3(2.2)), smoothstep(0.0, 0.4, -bounce.z));
            }
        }
    }
    float fresnel = (0.04 + 0.96 * pow(1.0 - nv, 5.0)) * (0.25 + 0.75 * smoothstep(0.02, 0.10, thick));
    colour = mix(colour, mirrored, clamp(fresnel, 0.0, 1.0));
    float ndh = max(dot(nW, normalize(sun + VW)), 0.0);
    colour += sunLight * (pow(ndh, 2600.0) * 7.0 + pow(ndh, 520.0) * 0.40);

    // the outline is one pixel soft: there the goo is mixed with the (shaded) world beside it by hand, the pixel is replaced
    colour = mix(shaded * shade, clamp(colour, 0.0, 1.0), cover);
    fragColor = vec4(pow(colour, vec3(1.0 / 2.2)), 1.0);
}
