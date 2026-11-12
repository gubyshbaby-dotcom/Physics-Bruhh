#ifndef PHYSICSBRUHH_LAVA_FRAME_GLSL
#define PHYSICSBRUHH_LAVA_FRAME_GLSL

// What the lava's shaders know about the frame.
layout(std140) uniform LavaFrame {
    mat4 LavaView;     // turns world axes into camera axes
    vec4 LavaClock;    // x: seconds, for heights on the move; y: seconds, for what drifts (starts over now and then); z: seconds a height takes to get where it is going; w: 1 for the finer look, 0 for the plainer one
    vec4 LavaSet;      // x: how fast the crust is carried along where lava runs, blocks a second; y: brightness; z: 1 where the sky gives no light at all (the Nether); w: unused
};

// Kinds of face (the mesh says which; LavaMesher).
const int LAVA_TOP = 0, LAVA_SIDE = 1, LAVA_UNDER = 2, LAVA_FALL = 3;

#endif
