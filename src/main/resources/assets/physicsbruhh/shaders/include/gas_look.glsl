#ifndef PHYSICSBRUHH_GAS_LOOK_GLSL
#define PHYSICSBRUHH_GAS_LOOK_GLSL

// How the gas is seen. The numbers are those of native/examples/gas_preview.rs, where they were chosen.

// Brightness of gas at flame heat, per block of it looked through, and how steeply that falls as the gas cools.
const float GAS_GLOW = 22.0;
const float GAS_GLOW_STEEP = 11.0;
// How much a block of smoke of density 1 dims what is behind it (e-folds), and the share of that light it scatters on rather than swallows.
const float GAS_SMOKE_DIMS = 9.0;
const float GAS_SMOKE_WHITE = 0.82;
// Flames are nearly clear: this much dimming per block at flame heat.
const float GAS_FLAME_DIMS = 0.6;
// How much of the light smoke scatters goes on forwards (Henyey-Greenstein g).
const float GAS_FORWARD = 0.45;

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
    return bright * mix(vec3(1.0, green, min(0.147 * k * k, 0.5)), vec3(0.05 + 0.35 * t * t, 0.35 + 0.5 * t, 1.0), soul);
}

float gasPhase(float cosine) {
    float g = GAS_FORWARD;
    return (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * cosine, 1.5);
}

#endif
