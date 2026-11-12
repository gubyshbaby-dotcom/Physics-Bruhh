#ifndef PHYSICSBRUHH_LAVA_WAVES_GLSL
#define PHYSICSBRUHH_LAVA_WAVES_GLSL

// The lava's surface as a heavy liquid (lava_waves.fsh works it out): what a corner of the surface
// is told by it. Needs lava_frame.glsl and the two textures.

uniform sampler2D LavaWavesNow;   // r: how far the surface stands above (below) where it rests, blocks; g: how fast it rises; b: how lately it was stirred, 0..1
uniform sampler2D LavaWavesMap;   // a pixel a block: rg: the height the lava's top rests at there (blocks above LavaGrid.w, plus one, times 512, as two bytes); nothing: no lava

// The height the lava rests at in the block column a place of the grid is in (0..1 across the grid): blocks above LavaGrid.w; negative: no lava there.
float lavaRest(vec2 g) {
    vec4 map = textureLod(LavaWavesMap, g, 0.0);
    float code = floor(map.r * 255.0 + 0.5) * 256.0 + floor(map.g * 255.0 + 0.5);
    return code / 512.0 - 1.0;
}

// What the waves do to a corner of the top: `rel` is where it is, in blocks from the camera's block; `sway` how much
// of the waves it takes (0: none: it lies on the floor, or is no part of the top). Returns: x how much higher it
// stands, yz the slope of the waves there (in x and in z), w how lately the lava there was stirred.
vec4 lavaWave(vec3 rel, float sway) {
    if (sway <= 0.0 || LavaGrid.z <= 0.0) return vec4(0.0);
    vec2 g = (rel.xz - LavaGrid.xy) * LavaGrid.z;
    // (towards the rim of the square the waves are let go of, so that the surface beyond it fits on)
    float rim = min(min(g.x, 1.0 - g.x), min(g.y, 1.0 - g.y)) / (LavaGrid.z * 5.0);
    if (rim <= 0.0) return vec4(0.0);
    // only the lava the waves are worked out for: not lava a storey above or below it
    float rest = lavaRest(g);
    if (rest < 0.0 || abs(rest + LavaGrid.w - rel.y) > 0.75) return vec4(0.0);
    float keep = min(rim, 1.0) * smoothstep(0.0, 0.45, sway);
    float e = LavaGridSet.x;
    vec4 now = textureLod(LavaWavesNow, g, 0.0);
    float inX = textureLod(LavaWavesNow, g + vec2(e, 0.0), 0.0).r - textureLod(LavaWavesNow, g - vec2(e, 0.0), 0.0).r;
    float inZ = textureLod(LavaWavesNow, g + vec2(0.0, e), 0.0).r - textureLod(LavaWavesNow, g - vec2(0.0, e), 0.0).r;
    return vec4(now.r, inX * 0.5 * LavaGridSet.y, inZ * 0.5 * LavaGridSet.y, now.b) * keep;
}

#endif
