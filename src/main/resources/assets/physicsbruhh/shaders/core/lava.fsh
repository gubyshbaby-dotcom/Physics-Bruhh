#version 330
#extension GL_ARB_separate_shader_objects : require

// Lava, a pixel of it. What is worked out here is how hot the surface is at this spot; the colour
// and the brightness follow from the heat the way they do for anything that glows by being hot
// (dull red, then orange, then yellow going on white, and very much brighter the hotter), and what
// has cooled is a crust of dark rock that is lit like rock.
//
// The surface is a sheet of plates of crust with molten lava showing in the cracks between them.
// Where the lava stands still the plates are large and the cracks thin, with patches where hot lava
// wells up and the crust is thin; where it runs the crust is carried along and torn, the faster the
// more; falling lava has hardly any. The edge of a flow cools first.

#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <physicsbruhh:lava_frame.glsl>

uniform sampler2D Sampler2;   // the game's light map

layout(location = 0) in vec3 place;
layout(location = 1) in vec3 facing;
layout(location = 2) in vec4 look;
layout(location = 3) in vec3 flow;
layout(location = 4) in vec3 fromCamera;
layout(location = 5) in vec2 fogDistance;

layout(location = 0) out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x), mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    return 0.5 * noise(p) + 0.3 * noise(p * 2.03 + 17.3) + 0.2 * noise(p * 4.11 + 41.7);
}

// Plates: how far to the nearest crack between two of them (in cells), and a number of its own for the plate.
vec2 plates(vec2 p) {
    vec2 n = floor(p), f = fract(p);
    float nearest = 8.0, second = 8.0;
    vec2 which = n;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 g = vec2(float(i), float(j));
            vec2 r = g + hash22(n + g) - f;
            float d = dot(r, r);
            if (d < nearest) { second = nearest; nearest = d; which = n + g; }
            else if (d < second) second = d;
        }
    }
    return vec2(sqrt(second) - sqrt(nearest), hash12(which + 5.5));
}

// The crust at a spot of the pattern: how far to the nearest crack between two plates (blocks), the plate's own
// number, and the roughness of the rock.
vec3 crust(vec2 q) {
    vec2 w = q + 0.30 * vec2(fbm(q * 0.9 + 7.1), fbm(q * 0.9 - 3.7));
    vec2 large = plates(w * 0.8);
    return vec3(large.x / 0.8, large.y, fbm(w * 5.0));
}

// What something this hot gives off (0: rock that has cooled, 1: lava at its hottest): the colour of the glow
// goes from deep red to orange, and its strength rises very steeply.
vec3 glow(float heat) {
    vec3 colour = vec3(1.0, 0.05 + 0.43 * heat, 0.10 * heat * heat);
    return colour * exp(7.5 * (heat - 0.70));
}

void main() {
    int kind = int(flow.z + 0.5);
    vec3 N = normalize(facing);
    float t = LavaClock.y;
    float running = clamp(length(flow.xy), 0.0, 1.0);

    // where in the pattern this spot is, and how the crust travels there
    vec2 q, run;
    if (kind == LAVA_TOP || kind == LAVA_UNDER) {
        q = place.xz;
        run = flow.xy * LavaSet.x;
        // lava that stands still is not dead: it turns over slowly
        run += 0.07 * (vec2(noise(q * 0.11 + vec2(0.0, t * 0.011)), noise(q * 0.11 + vec2(31.7, -t * 0.011))) - 0.5);
    } else {
        q = vec2(dot(place.xz, vec2(-N.z, N.x)), place.y * (kind == LAVA_FALL ? 0.3 : 1.0));
        run = vec2(0.0, kind == LAVA_FALL ? -0.75 : 0.0);
    }

    // the crust is carried along; so that it does not stretch without end it is looked up twice, each time over a
    // short while of travel, and the two are mixed: cracks close in one place as they open in another
    const float CYCLE = 16.0;
    float phase = fract(t / CYCLE), other = fract(t / CYCLE + 0.5);
    float share = 1.0 - abs(2.0 * phase - 1.0);
    vec3 c = crust(q - run * (phase - 0.5) * CYCLE) * share + crust(q - run * (other - 0.5) * CYCLE) * (1.0 - share);

    // how hot the lava is here, under the crust
    float hot = look.x;
    if (kind == LAVA_TOP) {
        // wells of hot lava in a lake, drifting about
        hot += (1.0 - running) * 0.6 * (noise(q * 0.085 + t * vec2(0.004, 0.003)) - 0.42);
        // where it ends in the open it cools first; less so where it is on the move
        hot *= mix(mix(0.6, 0.85, running), 1.0, smoothstep(0.0, 0.8, look.y));
    }
    hot = clamp(hot + 0.3 * look.w, 0.0, 1.0);

    // The hotter the lava, the fewer of the plates have set at all: the rest of the surface is melt. Around a
    // plate that has set the heat falls off from the crack into the plate; a plate that is about to go under
    // still glows.
    float pixel = length(fwidth(q));
    float cover = clamp(1.45 - 1.3 * hot + 0.10 * (noise(q * 0.3 + t * 0.04) - 0.5), 0.08, 0.97);
    float set = 1.0 - smoothstep(cover - 0.06, cover + 0.06, c.y);
    float reach = mix(0.02, 0.12, hot) + 0.7 * pixel;
    float molten = 1.0 - set * (1.0 - exp(-c.x / reach));
    float late = clamp(c.y / cover, 0.0, 1.0);
    float plate = 0.22 + 0.24 * hot + 0.26 * late * late;
    float melt = 0.70 + 0.16 * hot + 0.20 * (c.z - 0.5);
    float heat = mix(plate, melt, molten);

    // the crust stands a little proud of the melt, and is rough
    float relief = (1.0 - molten) * (0.6 + 0.4 * c.y) + 0.25 * c.z;
    vec3 dx = dFdx(fromCamera), dy = dFdy(fromCamera);
    vec3 r1 = cross(dy, N), r2 = cross(N, dx);
    float det = dot(dx, r1);
    vec3 slope = (dFdx(relief) * r1 + dFdy(relief) * r2) * sign(det);
    vec3 Nb = normalize(abs(det) * N - 0.05 * slope / (1.0 + 30.0 * pixel));

    // cooled rock, lit by the sky and by the glow of the lava around it
    vec3 rock = mix(vec3(0.030, 0.027, 0.028), vec3(0.085, 0.076, 0.072), c.z);
    vec3 skyLight = sample_lightmap(Sampler2, ivec2(0, int(look.z * 240.0 + 0.5))).rgb * (1.0 - LavaSet.z);
    vec3 V = normalize(-fromCamera);
    float facingUp = 0.55 + 0.45 * Nb.y;
    vec3 lit = rock * skyLight * facingUp * 1.5;
    lit += rock * vec3(1.0, 0.33, 0.07) * (0.5 + 3.0 * molten);
    float graze = pow(1.0 - clamp(dot(Nb, V), 0.0, 1.0), 5.0);
    vec3 mirrored = reflect(-V, Nb);
    lit += skyLight * (0.03 + 0.5 * graze) * smoothstep(-0.1, 0.5, mirrored.y) * 0.35 * (1.0 - molten);

    vec3 colour = lit + (1.0 - exp(-glow(heat) * LavaSet.y));
    fragColor = apply_fog(vec4(colour, 1.0), fogDistance.x, fogDistance.y, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
