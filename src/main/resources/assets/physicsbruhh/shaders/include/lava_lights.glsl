#ifndef PHYSICSBRUHH_LAVA_LIGHTS_GLSL
#define PHYSICSBRUHH_LAVA_LIGHTS_GLSL

// Lava that flows as a body of its own lights what is around it. The game's light knows nothing of
// it (it is not a block), so the glow is worked out here: where the lava is, as a few points, each
// with how much lava it stands for and how hot that is.
const int LAVA_LIGHTS = 24;

layout(std140) uniform LavaLights {
    vec4 LavaLight[LAVA_LIGHTS];   // xyz: where (in the particles' coordinates), w: how strong
    vec4 LavaLightSet;             // x: how many of them are in use, y: how far a light reaches (blocks), z: how bright the glow is on the whole
};

#endif
