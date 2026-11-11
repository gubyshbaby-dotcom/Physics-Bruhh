#ifndef PHYSICSBRUHH_GAS_CARRY_GLSL
#define PHYSICSBRUHH_GAS_CARRY_GLSL

// Where the gas in a fine cell was a step ago (in fine cells), or the cell itself if that is inside a block:
// what would have come out of a block has stayed where it is.
vec3 gasCameFrom(sampler2D velocity, sampler2D world, ivec3 cell) {
    float fpb = GasStep.z * GasStep.w;
    vec3 p = vec3(cell) + 0.5;
    vec3 u = gasAt(velocity, GasCoarse, p / GasStep.w).xyz;
    vec3 q = p - GasStep.x * u * fpb;
    ivec3 block = ivec3(floor(max(q, vec3(0.0)) / fpb));
    if (all(lessThan(block, ivec3(GasBlocks.xyz))) && gasBlock(world, block).r > 0.5) return p;
    return q;
}

#endif
