#version 330
#extension GL_ARB_separate_shader_objects : require

// The solver's passes are drawn with this: not over the whole texture of a grid but over one
// rectangle for each layer of the part of the box that is computed this step (GasFrom..GasTo).
// A fire in a box of thousands of blocks fills a few dozen of them, and the rest costs nothing.
// Four corners a layer, in the order the game's index buffer for quads takes them.

#include <physicsbruhh:gas.glsl>

void main() {
#ifdef FINE
    vec4 grid = GasFine;
#else
    vec4 grid = GasCoarse;
#endif
    ivec3 lo = gasLow(grid), hi = gasHigh(grid);
    int layer = lo.y + gl_VertexIndex / 4;
    int corner = gl_VertexIndex % 4;
    if (layer > hi.y) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    int tiles = int(grid.w);
    vec2 tile = vec2(float(layer % tiles) * grid.x, float(layer / tiles) * grid.z);
    vec2 size = vec2(grid.w * grid.x, ceil(grid.y / grid.w) * grid.z);
    vec2 least = tile + vec2(lo.xz), past = tile + vec2(hi.xz) + 1.0;
    vec2 at = vec2((corner == 1 || corner == 2) ? past.x : least.x, corner >= 2 ? past.y : least.y);
    gl_Position = vec4(at / size * 2.0 - 1.0, 0.0, 1.0);
}
