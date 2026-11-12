#version 330
#extension GL_ARB_separate_shader_objects : require

// Lava that flows as a body of its own, last step: into the picture. Two things happen here.
//
// Where there is lava in front of the world, its surface is worked out (from the smoothed
// nearest-surface texture) and what it looks like: molten and bright where it is fresh, where it is
// on the move and where one looks along its skin; a crust of dark rock where it has cooled, first on
// top, in patches that belong to the lava and travel with it, glowing dully where the crust is thin
// and in the gaps between its grains.
//
// Everywhere else the world is lit by the lava near it: the game's own light knows nothing of this
// lava, so the glow on walls and floor is added here, from a few points that say where the lava is.

#include <minecraft:projection.glsl>
#include <physicsbruhh:goo_frame.glsl>
#include <physicsbruhh:lava_lights.glsl>

uniform sampler2D SceneSampler;        // the frame so far (a copy of it)
uniform sampler2D SceneDepthSampler;   // and its depth
uniform sampler2D FluidSampler;        // nearest lava surface per pixel, smoothed: r minus its distance along the view axis, g how much "lava here"
uniform sampler2D SkinSampler;         // what the surface carries: r pattern, g heat (both times b, the weight), a the grain

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// how much of the smoothed "lava here" makes the outline
const float OUTLINE_LEVEL = 0.74;

// The point of a pixel's line of sight that lies `along` blocks down the view axis, in camera space.
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

// What something this hot gives off: dull red, orange, yellow going on white, and very much more of it the hotter.
vec3 glow(float heat) {
    float h2 = heat * heat;
    vec3 colour = vec3(1.0, 0.02 + 0.55 * h2 * heat, 0.12 * h2 * h2 * heat);
    return colour * exp(8.0 * (heat - 0.55));
}

void main() {
    ivec2 here = ivec2(gl_FragCoord.xy);
    vec2 surface = texelFetch(FluidSampler, here, 0).rg;
    float along = -surface.r;
    mat4 unproject = inverse(ProjMat);
    vec2 ndc = texCoord * 2.0 - 1.0;
    float sceneHere = sceneAlong(unproject, texCoord);
    mat3 toWorld = transpose(mat3(GooView));
    // one pixel to the right / one row on, in clip space (rows may run up or down the screen)
    vec2 pixelStep = vec2(dFdx(ndc.x), dFdy(ndc.y));

    // the outline: where the smoothed "lava here" falls below a level; one pixel soft
    float present = surface.g;
    vec2 outward = -vec2(dFdx(present), dFdy(present));
    float steep = max(abs(outward.x) + abs(outward.y), 1.0e-5);
    float cover = along > GOO_NONE ? 0.0 : clamp((present - OUTLINE_LEVEL) / steep + 0.5, 0.0, 1.0);
    cover *= 1.0 - smoothstep(sceneHere + 0.01, sceneHere + 0.05, along);

    // ---- the world, lit by the lava near it
    vec3 scene = texture(SceneSampler, texCoord).rgb;
    float lights = LavaLightSet.x;
    float shine = 0.0;
    if (cover < 0.996 && lights > 0.5 && sceneHere < 2000.0) {
        vec3 P = viewAt(unproject, ndc, sceneHere);
        // which way the world faces here: from its depth, taking on each axis the neighbour that lies on the same face
        vec2 texel = 1.0 / vec2(textureSize(SceneDepthSampler, 0));
        float left = sceneAlong(unproject, texCoord - vec2(texel.x, 0.0)), right = sceneAlong(unproject, texCoord + vec2(texel.x, 0.0));
        float below = sceneAlong(unproject, texCoord - vec2(0.0, texel.y)), above = sceneAlong(unproject, texCoord + vec2(0.0, texel.y));
        vec3 dx = abs(right - sceneHere) < abs(sceneHere - left)
            ? viewAt(unproject, ndc + vec2(pixelStep.x, 0.0), right) - P
            : P - viewAt(unproject, ndc - vec2(pixelStep.x, 0.0), left);
        vec3 dy = abs(above - sceneHere) < abs(sceneHere - below)
            ? viewAt(unproject, ndc + vec2(0.0, pixelStep.y), above) - P
            : P - viewAt(unproject, ndc - vec2(0.0, pixelStep.y), below);
        vec3 n = normalize(cross(dx, dy));
        if (dot(n, P) > 0.0) {
            n = -n;
        }
        vec3 faces = toWorld * n;
        vec3 place = toWorld * P - GooOffset.xyz;
        float reach2 = LavaLightSet.y * LavaLightSet.y;
        for (int i = 0; i < LAVA_LIGHTS; i++) {
            if (float(i) >= lights) {
                break;
            }
            vec3 to = LavaLight[i].xyz - place;
            float d2 = dot(to, to);
            float fall = max(1.0 - d2 / reach2, 0.0);
            // lava is a broad light: what faces away from its middle still catches some of it
            float turned = clamp(dot(faces, to) * inversesqrt(d2 + 1.0e-4) * 0.7 + 0.3, 0.0, 1.0);
            shine += LavaLight[i].w * fall * fall * turned / (0.30 + d2);
        }
        shine *= LavaLightSet.z;
    }
    // what is lit shows its own colours; where the picture is black there is still something to light
    float seen = clamp(dot(scene, vec3(0.3, 0.5, 0.2)) * 3.5, 0.0, 1.0);
    vec3 lit = scene + vec3(1.0, 0.40, 0.09) * (1.0 - exp(-shine)) * (0.30 + 0.70 * seen);

    if (cover < 0.004) {
        if (shine < 0.004) {
            discard;
        }
        fragColor = vec4(lit, 1.0);
        return;
    }

    // ---- the lava: its surface, and which way it faces (camera space)
    ivec2 last = textureSize(FluidSampler, 0) - 1;
    float left = -texelFetch(FluidSampler, max(here - ivec2(1, 0), ivec2(0)), 0).r;
    float right = -texelFetch(FluidSampler, min(here + ivec2(1, 0), last), 0).r;
    float below = -texelFetch(FluidSampler, max(here - ivec2(0, 1), ivec2(0)), 0).r;
    float above = -texelFetch(FluidSampler, min(here + ivec2(0, 1), last), 0).r;
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
    // towards its outline the lava curves away, like the rim of a drop
    float rounded = 1.0 - smoothstep(OUTLINE_LEVEL, 0.985, present);
    if (rounded > 0.0) {
        n = normalize(n + vec3(outward * sign(pixelStep) / steep * 1.4 * rounded * rounded, 0.0));
    }
    vec3 V = normalize(-P);
    float facing = clamp(dot(n, V), 0.0, 1.0);
    float rim = 1.0 - facing;
    vec3 N = toWorld * n;
    float top = clamp(N.y, 0.0, 1.0);

    // what the surface carries here
    vec4 skin = texelFetch(SkinSampler, here, 0);
    float pattern = skin.b > 1.0e-3 ? skin.r / skin.b : 0.5;
    float heat = skin.b > 1.0e-3 ? skin.g / skin.b : 1.0;
    float grain = skin.a;

    // How much of the surface the crust has taken: more of it the cooler the lava, on top before the
    // flanks; where one looks along the skin, the glow of what is under it wins.
    float cool = 1.0 - heat;
    float crusted = clamp(cool * 1.25 + 0.20 * (top - 0.45) * smoothstep(0.02, 0.25, cool) - 0.45 * rim * rim, 0.0, 0.97);
    // The pattern says where: a spot is crust if its number is under the level. (The pattern's
    // numbers crowd around a half, so the level runs from below them all to above them all.)
    float level = mix(0.20, 0.84, crusted);
    float edge = pattern + 0.07 * (grain - 0.5) - level;
    float crust = 1.0 - smoothstep(-0.025, 0.025, edge);

    // the melt: yellow where it has just come out, orange as it cools; brighter where one looks along it
    float hot = 0.65 + 0.10 * heat + 0.05 * rim + 0.02 * (grain - 0.4) + 0.07 * smoothstep(0.0, 0.3, edge);
    // the crust: dark rock in grains; it glows dully where it is thin, at its edge and between its grains
    float thin = smoothstep(-0.14, 0.0, edge);
    float seam = 1.0 - smoothstep(0.10, 0.45, grain);
    float under = 0.20 + 0.36 * thin * thin + (0.16 + 0.14 * heat) * seam + 0.07 * rim;
    vec3 rock = vec3(0.075, 0.055, 0.048) * (0.35 + 1.1 * grain) * (0.55 + 0.45 * top);
    vec3 light = mix(glow(hot), glow(under), crust);
    vec3 colour = rock * crust + (1.0 - exp(-light * 1.0));

    fragColor = vec4(mix(lit, colour, cover), 1.0);
}
