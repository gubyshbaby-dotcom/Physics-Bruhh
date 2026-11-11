#version 330
#extension GL_ARB_separate_shader_objects : require

// Gas, after a step: what each block of the box holds, one pixel a block. Red the densest smoke in
// it, green the hottest gas (both counting one cell into the blocks around, so that a block with
// nothing here really shows nothing), blue all its smoke, alpha all its heat (in blocks' worth).
// The drawing skips blocks with nothing in them; the game reads the sums.

#include <physicsbruhh:gas.glsl>
#include <physicsbruhh:gas_look.glsl>

uniform sampler2D GasSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec3 b = gasCell(GasBlocks, ivec2(gl_FragCoord.xy));
    // (blocks outside the part of the box that is computed hold nothing)
    if (b.y < 0 || any(lessThan(b, ivec3(GasFrom.xyz))) || any(greaterThanEqual(b, ivec3(GasTo.xyz)))) { fragColor = vec4(0.0); return; }
    int fpb = int(GasStep.z * GasStep.w);
    ivec3 from = b * fpb;
    ivec3 last = ivec3(GasFine.xyz) - 1;
    vec2 most = vec2(0.0), sum = vec2(0.0);
    for (int y = -1; y <= fpb; y++) {
        for (int z = -1; z <= fpb; z++) {
            for (int x = -1; x <= fpb; x++) {
                ivec3 c = from + ivec3(x, y, z);
                if (any(lessThan(c, ivec3(0))) || any(greaterThan(c, last))) continue;
                vec2 v = gasValue(texelFetch(GasSampler, gasPixel(GasFine, c), 0)).rg;
                most = max(most, v);
                if (x >= 0 && y >= 0 && z >= 0 && x < fpb && y < fpb && z < fpb) sum += v;
            }
        }
    }
    float cell = 1.0 / float(fpb * fpb * fpb);
    fragColor = vec4(most.x > 0.002 ? most.x : 0.0, most.y > 0.1 ? most.y : 0.0, sum * cell);
}
