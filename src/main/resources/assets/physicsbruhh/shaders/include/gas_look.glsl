#ifndef PHYSICSBRUHH_GAS_LOOK_GLSL
#define PHYSICSBRUHH_GAS_LOOK_GLSL

// How the gas is seen. The numbers are those of native/examples/gas_preview.rs, where they were chosen.

// Brightness of gas at flame heat, per block of it looked through, and how steeply that falls as the gas cools.
const float GAS_GLOW = 70.0;
const float GAS_GLOW_STEEP = 13.0;
// How much a block of smoke of density 1 dims what is behind it (e-folds), and the share of that light it scatters on rather than swallows.
const float GAS_SMOKE_DIMS = 12.0;
const float GAS_SMOKE_WHITE = 0.62;
// Glowing soot stands in the way of what is behind it as well: this much dimming per block at flame heat.
const float GAS_FLAME_DIMS = 2.5;
// How much of the light smoke scatters goes on forwards (Henyey-Greenstein g).
const float GAS_FORWARD = 0.45;
// How much the detail finer than the grid changes the heat a flame is drawn with, and the smoke (shares).
const float GAS_FLAME_DETAIL = 0.42;
const float GAS_SMOKE_DETAIL = 0.75;
// How far a flame is drawn from where the grid has it, at most (blocks).
const float GAS_SWAY = 0.2;

// What a cell holds, from its texture: x smoke, y heat, z fuel, w how much of the fuel and heat is soul fire's.
// The processor's solver hands its grid over as bytes (CODED): square root of the smoke's share of the most, heat's share
// of the most, share of soul fire, fuel's share of the most.
vec4 gasValue(vec4 texel) {
#ifdef CODED
    float heat = texel.g * GasE.x, fuel = texel.a * GasE.y;
    return vec4(texel.r * texel.r * GasD.w, heat, fuel, texel.b * (heat + fuel));
#else
    return texel;
#endif
}

// Light the gas gives off at this heat, per block of it looked through: soot glowing at the gas's temperature (heat 1 is
// about 1800 K). The colour is that of a black body, the brightness climbs with temperature the way visible light does
// (steeply); soul fire burns blue.
vec3 gasFlame(float heat, float soul) {
    if (heat < 0.12) return vec3(0.0);
    float kelvin = 300.0 + 1500.0 * heat;
    float bright = GAS_GLOW * exp(GAS_GLOW_STEEP * (1.0 - 1800.0 / kelvin));
    float green = clamp(0.04 + 0.000208 * (kelvin - 1000.0), 0.0, 0.7);
    float k = max((kelvin - 1900.0) / 1100.0, 0.0);
    float t = clamp((heat - 0.2) / 0.9, 0.0, 1.0);
    return bright * mix(vec3(1.0, green, min(0.006 + 0.147 * k * k, 0.5)), vec3(0.05 + 0.35 * t * t, 0.35 + 0.5 * t, 1.0), soul);
}

// A smooth wavy pattern in space, about -1.4..1.4: cheap, and with no grid showing in it.
float gasWaves(vec3 p) {
    return dot(sin(p), cos(p.zxy));
}

// Lumps of two sizes, taller than wide (tongues): about 0..1, 0.5 on average. `coarse` and `fine` are lumps to the block.
float gasLumps3(vec3 p, float coarse, float fine) {
    vec3 q = p * vec3(3.14159265, 1.5707963, 3.14159265);
    float big = gasWaves(q * coarse);
    // the small lumps lie askew to the big ones and are pushed about by them
    vec3 r = mat3(0.0, 0.8, 0.6, -0.8, 0.36, -0.48, -0.6, -0.48, 0.64) * q * fine + big * 1.3;
    return clamp(0.5 + 0.25 * big + 0.17 * gasWaves(r), 0.0, 1.0);
}

// Detail finer than the grid can hold: a pattern of lumps that is carried up with the hot gas (the hotter, the faster).
// Two copies of it take turns: each starts afresh while the other is at full strength, so the pattern is never
// stretched out of shape and never jumps. `seconds` is the clock, `coarse` and `fine` are lumps to the block.
float gasDetail(vec3 p, float heat, float seconds, float coarse, float fine) {
    const float TURN = 0.8;
    vec3 rise = vec3(0.0, 5.0 * sqrt(max(heat, 0.0)), 0.0);
    float turns = seconds / TURN;
    float a = fract(turns), b = fract(turns + 0.5);
    float first = gasLumps3(p - rise * (a * TURN) + vec3(floor(turns) * 3.7), coarse, fine);
    float second = gasLumps3(p - rise * (b * TURN) + vec3(floor(turns + 0.5) * 3.7 + 11.3), coarse, fine);
    return mix(second, first, 1.0 - abs(2.0 * a - 1.0));
}

// How far sideways (x, z) a flame is drawn from where the grid has it: tongues waver, more the higher up the frequency.
vec2 gasSway(vec3 p, float seconds) {
    vec3 w = (p * vec3(1.0, 0.55, 1.0) - vec3(0.0, 2.2 * seconds, 0.0)) * 4.6;
    vec3 v = mat3(0.0, 0.8, 0.6, -0.8, 0.36, -0.48, -0.6, -0.48, 0.64) * w * 2.3 + 1.9;
    return vec2(gasWaves(w) + 0.5 * gasWaves(v), gasWaves(w.yzx + 2.4) + 0.5 * gasWaves(v.zxy + 5.1)) / 2.1;
}

float gasPhase(float cosine) {
    float g = GAS_FORWARD;
    return (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * cosine, 1.5);
}

#endif
