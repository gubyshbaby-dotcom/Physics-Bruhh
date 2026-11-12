#version 330
#extension GL_ARB_separate_shader_objects : require

// Lava, a pixel of it. What is worked out here is how hot the surface is at this spot; the colour
// and the brightness follow from the heat the way they do for anything that glows by being hot
// (dull red, then orange, then yellow going on white, and very much brighter the hotter), and what
// has cooled is a crust of dark rock that is lit like rock.
//
// The look is taken from photographs of lava. Lava on the run is a bright sheet, finely wrinkled
// across the way it runs and drawn out into bands along it, with a few small rafts of crust that
// travel with it; towards its edges and where it is thin it cools and the crust closes. Lava that
// stands still has set into large plates with glowing cracks between them, hairlines of glow along
// the folds of the crust, and patches where hot lava wells up and the plates have gone under.
// Falling lava is a bright stream with hardly any crust.

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

// Two numbers that change smoothly from place to place.
vec2 noise2(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash22(i), hash22(i + vec2(1.0, 0.0)), u.x), mix(hash22(i + vec2(0.0, 1.0)), hash22(i + vec2(1.0, 1.0)), u.x), u.y);
}

// Noise drawn out along a direction (by as much as the lava runs): what a liquid that is sheared does to anything on it.
float drawn(vec2 p, vec2 along) {
    return (noise(p - along * 1.3) + noise(p - along * 0.65) + noise(p) + noise(p + along * 0.65) + noise(p + along * 1.3)) * 0.2;
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

// The surface at a spot of the pattern: how far to the nearest crack between two plates (blocks), the plate's own
// number, the bands the lava is drawn out into along the way it runs, and the fine wrinkles on it, drawn out the
// same way (where it does not run neither has a direction); and its mottling.
vec4 surface(vec2 q, vec2 way, float running, out float mottle) {
    vec2 n = noise2(q * 0.9);
    vec2 w = q + 0.45 * n + 0.2 * noise2(q * 2.1 + 9.0);
    vec2 plate = plates(w * 0.85);
    vec2 along = way * running;
    vec2 p = w * 13.0;
    float wrinkles = (noise(p - along) + noise(p) + noise(p + along)) / 3.0;
    mottle = noise(w * 1.3 + 3.3);
    return vec4(plate.x / 0.85, plate.y, drawn(w * 5.5, along), wrinkles);
}

// What something this hot gives off (0: rock that has cooled, 1: lava at its hottest): the glow goes from deep red
// through orange to yellow and on to white, and its strength rises very steeply.
vec3 glow(float heat) {
    float h2 = heat * heat;
    vec3 colour = vec3(1.0, 0.02 + 0.55 * h2 * heat, 0.12 * h2 * h2 * heat);
    return colour * exp(8.0 * (heat - 0.55));
}

void main() {
    int kind = int(flow.z + 0.5);
    vec3 N = normalize(facing);
    float t = LavaClock.y;
    float running = clamp(length(flow.xy), 0.0, 1.0);

    // where in the pattern this spot is, and how the surface travels there
    vec2 q, run, way;
    float across = 1.0, stream = 0.0;
    if (kind == LAVA_TOP || kind == LAVA_UNDER) {
        q = place.xz;
        way = running > 0.02 ? normalize(flow.xy) : vec2(1.0, 0.0);
        run = flow.xy * LavaSet.x;
        // lava that stands still is not dead: it turns over slowly
        run += 0.07 * (noise2(q * 0.11 + vec2(0.0, t * 0.011)) - 0.5);
    } else {
        float u = dot(place.xz, vec2(-N.z, N.x));
        bool falls = kind == LAVA_FALL;
        q = vec2(u, place.y * (falls ? 0.45 : 1.0));
        way = vec2(0.0, 1.0);
        running = falls ? 1.0 : 0.0;
        run = vec2(0.0, falls ? -1.1 : 0.0);
        // a falling stream is round: towards the corners of its block it turns away and is seen less hot
        if (falls) { across = smoothstep(0.52, 0.22, abs(fract(u) - 0.5)); stream = 1.0; }
    }

    // the surface is carried along; so that it does not stretch without end it is looked up twice, each time over a
    // short while of travel, and the two are mixed: crust closes in one place as it opens in another
    const float CYCLE = 16.0;
    float phase = fract(t / CYCLE), other = fract(t / CYCLE + 0.5);
    float share = 1.0 - abs(2.0 * phase - 1.0);
    float mottleA, mottleB;
    vec4 c = surface(q - run * (phase - 0.5) * CYCLE, way, running, mottleA) * share + surface(q - run * (other - 0.5) * CYCLE, way, running, mottleB) * (1.0 - share);
    float mottle = mottleA * share + mottleB * (1.0 - share);

    // how hot the lava is here, under the crust
    float hot = look.x;
    if (kind == LAVA_TOP) {
        // wells of hot lava in a lake, drifting about
        hot += (1.0 - running) * 0.55 * (noise(q * 0.085 + t * vec2(0.004, 0.003)) - 0.5);
        // where it ends in the open it cools first; less so where it is on the move
        hot *= mix(mix(0.6, 0.86, running), 1.0, smoothstep(0.0, 0.8, look.y));
    }
    hot = clamp(hot + 0.3 * look.w, 0.0, 1.0);

    // The hotter the lava, the fewer of the plates have set at all, and those that have are small rafts with melt
    // all around; the cooler, the more of them, until they lie edge to edge with only cracks between. Around
    // the edge of a plate the heat falls off into it; a plate that is about to go under still glows.
    float pixel = length(fwidth(q));
    float cover = clamp(2.1 - 2.2 * hot + 0.10 * (mottle - 0.5), 0.03, 0.96);
    float chosen = 1.0 - smoothstep(cover - 0.05, cover + 0.05, c.y);
    float inset = 0.17 * (1.0 - cover);
    float reach = mix(0.018, 0.06, hot) + 0.7 * pixel;
    float set = chosen * (1.0 - exp(-max(c.x - inset, 0.0) / reach));
    float fine = 1.0 / (1.0 + 6.0 * pixel);

    // wrinkles: short strokes that lie along the way the lava runs, some cooler than what is around them, some hotter
    float cooler = fine * smoothstep(0.50, 0.36, c.w), hotter = fine * smoothstep(0.52, 0.68, c.w);

    // the melt: mottled, banded along the way it runs, finely streaked
    // (a falling stream is all melt, bright and even, redder where it turns away at its sides)
    float melt = 0.70 + 0.06 * hot + 0.05 * stream + 0.14 * (mottle - 0.5) + mix(0.55, 0.3, stream) * (c.z - 0.5)
            - mix(0.11, 0.05, stream) * cooler + 0.05 * hotter - 0.13 * (1.0 - across);
    // (the skin of a bubble and the drops it throws are thin, and seen right through to the heat inside)
    melt += 0.09 * max(look.y - 1.0, 0.0);
    // the crust: cold, but along its wrinkles, where it is thin or torn, the glow of what is under it comes through
    float late = clamp(c.y / cover, 0.0, 1.0);
    float crustHeat = 0.10 + 0.16 * hot + 0.22 * late * late + running * (0.22 + 0.30 * hot) * hotter * smoothstep(0.45, 0.6, c.z + 0.1 * hot);
    float heat = min(mix(melt, crustHeat, set), 0.9);

    // the crust stands a little proud of the melt; both are wrinkled along the streaks
    float relief = 0.6 * set + (0.5 + 1.0 * set) * (c.z - 0.5) + (0.5 + 0.8 * set) * (c.w - 0.5) + 0.1 * mottle;
    vec3 dx = dFdx(fromCamera), dy = dFdy(fromCamera);
    vec3 r1 = cross(dy, N), r2 = cross(N, dx);
    float det = dot(dx, r1);
    vec3 slope = (dFdx(relief) * r1 + dFdy(relief) * r2) * sign(det);
    vec3 Nb = normalize(abs(det) * N - 0.06 * slope / (1.0 + 30.0 * pixel));

    // cooled rock, lit by the sky and by the glow of the lava around it: dark, a little blue, with a dull shine
    vec3 rock = mix(vec3(0.030, 0.031, 0.036), vec3(0.085, 0.086, 0.094), mottle);
    vec3 skyLight = sample_lightmap(Sampler2, ivec2(0, int(look.z * 240.0 + 0.5))).rgb * (1.0 - LavaSet.z);
    vec3 V = normalize(-fromCamera);
    float facingUp = 0.5 + 0.5 * Nb.y;
    vec3 lit = rock * skyLight * facingUp * 1.6;
    lit += rock * vec3(1.0, 0.33, 0.07) * (0.4 + 2.5 * (1.0 - set));
    float graze = pow(1.0 - clamp(dot(Nb, V), 0.0, 1.0), 5.0);
    vec3 mirrored = reflect(-V, Nb);
    lit += skyLight * (0.04 + 0.5 * graze) * smoothstep(-0.1, 0.5, mirrored.y) * 0.4 * set;

    vec3 colour = lit * set + (1.0 - exp(-glow(heat) * LavaSet.y));
    fragColor = apply_fog(vec4(colour, 1.0), fogDistance.x, fogDistance.y, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
