#ifndef PHYSICSBRUHH_GAS_VIEW_GLSL
#define PHYSICSBRUHH_GAS_VIEW_GLSL

// Where the gas is looked at from, and the light of the day.
layout(std140) uniform GasView {
    mat4 GasViewMat;    // turns world axes into camera axes
    vec4 GasEye;        // xyz: the camera, in blocks from the corner of the box, w: 1 if depth runs 0..1 in clip space, 0 if -1..1
    vec4 GasSun;        // xyz: direction towards the sun (the moon at night), w: a number that changes every frame
    vec4 GasSunLight;   // rgb: colour and strength of its light, a: seconds, for detail that drifts
    vec4 GasSkyLight;   // rgb: colour and strength of the light of the sky
};

#endif
