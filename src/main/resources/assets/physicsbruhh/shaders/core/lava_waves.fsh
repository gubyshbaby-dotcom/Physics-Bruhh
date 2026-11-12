#version 330
#extension GL_ARB_separate_shader_objects : require

// The lava's surface as a heavy liquid: a step of its waves, a pixel a cell (four cells to the block)
// of a square of the world around the player. Each cell holds how far the surface stands above or
// below where it rests, how fast it is rising, and how lately it was stirred. What the surface does:
//  - gravity: where it stands higher than around it, it is pulled down, and what is around it is
//    pushed up; so a hump or a hollow spreads as a wave;
//  - lava is thick: its motion dies away quickly, short ripples at once, and a wave does not get far;
//  - it is held by its banks: a wave is thrown back by them, and lava a storey higher or lower is another pool;
//  - what pushes it (a bubble swelling and bursting, something falling in or wading through) is
//    handed over as a short list of places, each so wide and so hard.

#include <physicsbruhh:lava_frame.glsl>

uniform sampler2D InSampler;      // the step before (the same three numbers)
uniform sampler2D LavaWavesMap;   // where the lava is and the height it rests at, a pixel a block (lavaRest below)

layout(std140) uniform LavaWavesStep {
    vec4 StepSet;        // x: seconds; y: how many cells the square has moved in x since the step before, z: in z; w: how many pushes
    vec4 StepPush[16];   // x, z: where, in cells from the square's least corner; z: how wide, in cells; w: how hard, blocks a second (up or down)
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// how fast a wave runs (blocks a second), how quickly motion dies away (per second), how quickly short ripples die
// (blocks squared a second), and how long lava that was stirred stays hot (seconds)
const float SPEED = 2.2, DRAG = 0.9, THICK = 0.16, COOLS = 7.0;

float rest(vec2 g) {
    vec4 map = textureLod(LavaWavesMap, g, 0.0);
    return (floor(map.r * 255.0 + 0.5) * 256.0 + floor(map.g * 255.0 + 0.5)) / 512.0 - 1.0;
}

void main() {
    float e = LavaGridSet.x, cells = LavaGridSet.y, dt = StepSet.x;
    float here = rest(texCoord);
    if (here < 0.0) { fragColor = vec4(0.0); return; }
    // where this cell was in the step before (the square follows the player)
    vec2 was = texCoord + StepSet.yz * e;
    vec4 mine = (was.x < 0.0 || was.x > 1.0 || was.y < 0.0 || was.y > 1.0) ? vec4(0.0) : textureLod(InSampler, was, 0.0);
    // the four cells around it; a bank, or lava of another pool, gives back what this cell has (a wave is thrown back)
    vec2 sum = vec2(0.0);
    for (int k = 0; k < 4; k++) {
        vec2 d = k == 0 ? vec2(e, 0.0) : k == 1 ? vec2(-e, 0.0) : k == 2 ? vec2(0.0, e) : vec2(0.0, -e);
        vec2 at = texCoord + d, from = was + d;
        float there = (at.x < 0.0 || at.x > 1.0 || at.y < 0.0 || at.y > 1.0) ? -1.0 : rest(at);
        bool open = there >= 0.0 && abs(there - here) < 0.6 && from.x >= 0.0 && from.x <= 1.0 && from.y >= 0.0 && from.y <= 1.0;
        sum += open ? textureLod(InSampler, from, 0.0).rg : mine.rg;
    }
    vec2 bend = (sum - 4.0 * mine.rg) * cells * cells;
    float rising = mine.g + dt * (SPEED * SPEED * bend.x - DRAG * mine.g + THICK * bend.y);
    float stirred = mine.b * exp(-dt / COOLS);
    vec2 cell = texCoord / e;
    int pushes = int(StepSet.w + 0.5);
    for (int i = 0; i < pushes; i++) {
        vec4 p = StepPush[i];
        vec2 off = (cell - p.xy) / max(p.z, 0.5);
        float far = 2.0 * dot(off, off), w = exp(-far);
        // (what is pushed down in the middle comes up around it: the lava is moved, not made or lost)
        rising += p.w * w * (1.0 - far);
        stirred = max(stirred, min(1.0, abs(p.w) * 1.2) * w);
    }
    rising = clamp(rising, -5.0, 5.0);
    float high = clamp(mine.r + dt * rising, -0.4, 0.6);
    fragColor = vec4(high, rising, stirred, 1.0);
}
