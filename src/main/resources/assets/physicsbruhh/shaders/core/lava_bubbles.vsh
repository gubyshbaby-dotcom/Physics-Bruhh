#version 330
#extension GL_ARB_separate_shader_objects : require

// Lava that bubbles: gas comes up through a deep pool, lifts the surface into a dome, the dome
// bursts, and what was its skin is thrown up as drops that fly and fall back. Every bubble is two
// texels (where, how large, when it began, how long it swells); the dome and the drops are made
// here, corner by corner, from the number of the corner. A drop is a body thrown at a speed of its
// own under gravity: where it is at any moment follows from when and how it was thrown.
// The pixels are the lava's own (lava.fsh).

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <physicsbruhh:lava_frame.glsl>

uniform samplerBuffer LavaBubbles;   // texel 0: the section places are counted from; then per bubble: x, y, z, radius | when it began, seconds it swells, a number of its own, light of the sky

layout(location = 0) out vec3 place;
layout(location = 1) out vec3 facing;
layout(location = 2) out vec4 look;
layout(location = 3) out vec3 flow;
layout(location = 4) out vec3 fromCamera;
layout(location = 5) out vec2 fogDistance;

// (the same numbers as LavaBubbles.java)
const int RINGS = 5, AROUND = 12, DROPS = 8, DROP_RINGS = 3, DROP_AROUND = 6;
const int DOME_FACES = RINGS * AROUND, DROP_FACES = DROP_RINGS * DROP_AROUND, FACES = DOME_FACES + DROPS * DROP_FACES;
const float GRAVITY = 14.0;

vec4 bubble(int at) {
    return texelFetch(LavaBubbles, at);
}

float chance(float seed, float n) {
    return fract(sin(seed * 91.7 + n * 37.3) * 43758.5453);
}

void main() {
    int face = gl_VertexIndex / 4, k = gl_VertexIndex % 4;
    int which = face / FACES, part = face % FACES;
    vec4 head = bubble(0), a = bubble(1 + which * 2), b = bubble(2 + which * 2);
    float age = LavaClock.x - b.x, swell = b.y, seed = b.z, radius = a.w;
    // the corner of its face: across (around the dome) and up
    vec2 c = vec2((k == 2 || k == 3) ? 1.0 : 0.0, (k == 1 || k == 2) ? 1.0 : 0.0);

    vec3 local, normal;
    vec2 run;
    float heat, thin;
    bool gone = age < 0.0;
    if (part < DOME_FACES) {
        float up = (float(part / AROUND) + c.y) / float(RINGS) * 1.5708;
        float round_ = (float(part % AROUND) + c.x) / float(AROUND) * 6.2832;
        float s = clamp(age / swell, 0.0, 1.0);
        float grown = s * s * (3.0 - 2.0 * s);
        // it starts as a low wide swelling and rises to a dome; just before it goes it trembles
        float wide = radius * (0.62 + 0.38 * grown);
        float high = radius * (0.06 + 0.94 * grown) * (1.0 + 0.05 * s * sin(age * 31.0 + seed * 6.0));
        // once it has burst the skin is gone in a moment: it opens from the top and falls back into a ring
        float burst = clamp((age - swell) / 0.16, 0.0, 1.0);
        if (burst >= 1.0) gone = true;
        up = min(up, 1.5708 * (1.0 - burst));
        high *= 1.0 - 0.85 * burst;
        wide *= 1.0 + 0.25 * burst;
        vec3 unit = vec3(cos(up) * cos(round_), sin(up), cos(up) * sin(round_));
        local = a.xyz + vec3(unit.x * wide, unit.y * high - 0.02, unit.z * wide);
        normal = normalize(vec3(unit.x / wide, unit.y / max(high, 0.03), unit.z / wide));
        // (its skin is thinnest, and so brightest, at the top)
        run = vec2(0.0);
        heat = 1.0;
        thin = 0.25 + 0.75 * unit.y * grown;
    } else {
        int drop = (part - DOME_FACES) / DROP_FACES, at = (part - DOME_FACES) % DROP_FACES;
        float up = ((float(at / DROP_AROUND) + c.y) / float(DROP_RINGS) - 0.5) * 3.1416;
        float round_ = (float(at % DROP_AROUND) + c.x) / float(DROP_AROUND) * 6.2832;
        vec3 unit = vec3(cos(up) * cos(round_), sin(up), cos(up) * sin(round_));
        float flown = age - swell;
        float r1 = chance(seed, float(drop)), r2 = chance(seed, float(drop) + 11.0), r3 = chance(seed, float(drop) + 23.0);
        float turn = (float(drop) + r1) / float(DROPS) * 6.2832;
        // thrown up and outwards, the harder the larger the bubble was
        float outwards = (0.3 + 1.2 * r2) * sqrt(radius * 2.0), upwards = (2.4 + 2.8 * r3) * sqrt(radius * 2.0);
        vec3 thrown = vec3(cos(turn) * outwards, upwards, sin(turn) * outwards);
        vec3 from = a.xyz + vec3(cos(turn) * 0.5, 0.5, sin(turn) * 0.5) * radius;
        vec3 centre = from + thrown * flown - vec3(0.0, 0.5 * GRAVITY * flown * flown, 0.0);
        vec3 speed = thrown - vec3(0.0, GRAVITY * flown, 0.0);
        if (flown < 0.0 || centre.y < a.y - 0.06) gone = true;
        float size = radius * (0.09 + 0.13 * r1) * (1.0 - 0.3 * clamp(flown, 0.0, 1.0));
        // a drop is drawn out along the way it flies
        vec3 way = normalize(speed + vec3(0.0, 1.0e-4, 0.0));
        local = centre + (unit + way * dot(unit, way) * min(1.5, 0.14 * length(speed))) * size;
        normal = unit;
        run = vec2(0.0);
        heat = 1.0 - 0.3 * clamp(flown / 1.2, 0.0, 1.0);
        thin = 0.7 - 0.5 * clamp(flown / 1.2, 0.0, 1.0);
    }

    ivec3 corner0 = ivec3(floor(head.xyz + 0.5)) * 16;
    vec3 pos = local + vec3(corner0 - CameraBlockPos) + CameraOffset;
    gl_Position = gone ? vec4(2.0, 2.0, 2.0, 1.0) : ProjMat * LavaView * vec4(pos, 1.0);

    facing = normal;
    place = vec3(corner0 & 1023) + local;
    look = vec4(heat, 1.0 + thin, b.w, 1.0);
    flow = vec3(run, float(LAVA_TOP));
    fromCamera = pos;
    fogDistance = vec2(fog_spherical_distance(pos), fog_cylindrical_distance(pos));
}
