#version 330
#extension GL_ARB_separate_shader_objects : require

// The passes over the fine grid (smoke, heat, fuel) are drawn with this: one rectangle for every
// layer of every block of the part of the box that is computed, and none where the block is not
// awake (gasAwake in gas.glsl): where nothing burns and no gas is near, nothing is drawn at all,
// and the texture, cleared before the pass, stays empty there. Even inside the part that is
// computed most of the air holds nothing: over one fire in a box of five thousand blocks, four
// hundred are awake.
// GAS_KEPT: the texture was not cleared before the pass (it holds nothing outside the part that is
// computed, so there was no need: GasGpu). Then a block that is not awake is drawn after all, and
// what is drawn there is nothing: whatever the texture held in it from a few steps ago is gone,
// at the price of writing zeros over the part instead of over the whole texture.
// Four corners a rectangle, in the order the game's index buffer for quads takes them.

#include <physicsbruhh:gas.glsl>

uniform sampler2D WorldSampler;
uniform sampler2D InfoSampler;    // what each block held after the last step (gas_where.fsh)

#ifdef GAS_KEPT
layout(location = 0) flat out float gasAsleep;
#endif

void main() {
    int cells = int(GasStep.z * GasStep.w);
    ivec3 from = ivec3(GasFrom.xyz), size = ivec3(GasTo.xyz) - from;
    int rectangle = gl_VertexIndex / 4, corner = gl_VertexIndex % 4;
    int number = rectangle / cells;
    ivec3 block = from + ivec3(number % size.x, number / (size.x * size.z), (number / size.x) % size.z);
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    if (number >= size.x * size.y * size.z) return;
    vec4 what = gasBlock(WorldSampler, block);
    // (the middle of the block stands for all of it: a blob that reaches any of it reaches to within half its diagonal of the middle)
    bool asleep = what.r > 0.5 || !gasAwake(InfoSampler, what, block, vec3(block) + 0.5, 0.87);
#ifdef GAS_KEPT
    gasAsleep = asleep ? 1.0 : 0.0;
#else
    if (asleep) return;
#endif
    int layer = block.y * cells + rectangle % cells;
    int tiles = int(GasFine.w);
    vec2 tile = vec2(float(layer % tiles) * GasFine.x, float(layer / tiles) * GasFine.z);
    vec2 size2 = vec2(GasFine.w * GasFine.x, ceil(GasFine.y / GasFine.w) * GasFine.z);
    vec2 least = tile + vec2(block.xz * cells), past = least + float(cells);
    vec2 at = vec2((corner == 1 || corner == 2) ? past.x : least.x, corner >= 2 ? past.y : least.y);
    gl_Position = vec4(at / size2 * 2.0 - 1.0, 0.0, 1.0);
}
