#ifndef PHYSICSBRUHH_GOO_FRAME_GLSL
#define PHYSICSBRUHH_GOO_FRAME_GLSL

// What the liquid passes know about the frame. Positions of goo particles are kept relative to an
// origin near the goo; everything else is seen from the camera.
layout(std140) uniform GooFrame {
    mat4 GooView;      // turns world axes into camera axes
    vec4 GooOffset;    // xyz: the particles' origin as seen from the camera, w: radius a particle is drawn with
    vec4 GooSun;       // xyz: direction towards the sun (the moon at night), w: 1 if depth runs 0..1 in clip space, 0 if -1..1
    vec4 GooSky;       // rgb: colour of the sky overhead, a: seconds, for things that drift
    vec4 GooHorizon;   // rgb: colour of the sky at the horizon, a: daylight, 0..1
    vec4 GooLight;     // rgb: colour and strength of the sun's (moon's) light, a: distance between neighbouring particles
};

// The surface texture holds, in red, minus the distance of the nearest goo along the view axis.
// Anything further than this is "no goo here".
const float GOO_NONE = 5000.0;

#endif
