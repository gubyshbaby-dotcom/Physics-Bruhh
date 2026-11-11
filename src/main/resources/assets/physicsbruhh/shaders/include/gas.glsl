#ifndef PHYSICSBRUHH_GAS_GLSL
#define PHYSICSBRUHH_GAS_GLSL

// Smoke and fire on a grid, on the graphics card. The same steps, in the same order and with the
// same numbers, as native/src/gas.rs (which says what each of them is for); one pass over a
// texture per step.
//
// A grid lies in its texture as layers of constant height side by side, `tiles` of them to a row;
// cell (x, y, z) is the pixel (x + (y mod tiles) * nx, z + (y / tiles) * nz).

#define GAS_BLOBS 16

layout(std140) uniform GasGrid {
    vec4 GasCoarse;   // the coarse grid (velocity, pressure): cells along x, y, z, and tiles to a row of its texture
    vec4 GasFine;     // the fine grid (smoke, heat, fuel), the same way
    vec4 GasBlocks;   // the map of the world in the box, one pixel a block, the same way
    vec4 GasStep;     // x: seconds this step, y: seconds since the gas began, z: coarse cells to a block, w: fine cells to a coarse one
    vec4 GasMoved;    // xyz: blocks the box has moved since the last step, w: how many blobs there are
    vec4 GasA;        // buoyancy, weight, cool, cool4            (the numbers of the model, see gas.rs)
    vec4 GasB;        // ignite, burn, heat, soot
    vec4 GasC;        // smoke_fade, drag, confine, swell
    vec4 GasD;        // unrest, rounds, edge, most_smoke
    vec4 GasE;        // most_heat, most_fuel
    vec4 GasBlob[GAS_BLOBS * 3];   // a blob: centre (blocks from the corner of the box) and radius; fuel, smoke, heat, soul; velocity and how much gas it makes
};

// Pressure "in a block": a number no real pressure comes near.
const float GAS_WALL = -60000.0;
const float GAS_WALL_TEST = -30000.0;

// The cell a pixel of a grid's texture stands for; y is -1 on the unused tiles after the last layer.
ivec3 gasCell(vec4 grid, ivec2 pixel) {
    ivec3 n = ivec3(grid.xyz);
    ivec2 tile = ivec2(pixel.x / n.x, pixel.y / n.z);
    int y = tile.x + tile.y * int(grid.w);
    return ivec3(pixel.x - tile.x * n.x, y < n.y ? y : -1, pixel.y - tile.y * n.z);
}

ivec2 gasPixel(vec4 grid, ivec3 cell) {
    ivec3 n = ivec3(grid.xyz);
    int tiles = int(grid.w);
    return ivec2(cell.x + (cell.y % tiles) * n.x, cell.z + (cell.y / tiles) * n.z);
}

bool gasInside(vec4 grid, ivec3 cell) {
    return all(greaterThanEqual(cell, ivec3(0))) && all(lessThan(cell, ivec3(grid.xyz)));
}

// A cell's own value; cells outside the grid answer with the nearest one inside.
vec4 gasFetch(sampler2D field, vec4 grid, ivec3 cell) {
    return texelFetch(field, gasPixel(grid, clamp(cell, ivec3(0), ivec3(grid.xyz) - 1)), 0);
}

// The value at a point given in cells (the middle of cell i is at i + 0.5), between the eight cells around it.
vec4 gasAt(sampler2D field, vec4 grid, vec3 at) {
    vec3 n = grid.xyz;
    at = clamp(at, vec3(0.5), n - 0.5);
    float low = floor(at.y - 0.5);
    float high = min(low + 1.0, n.y - 1.0);
    vec2 size = vec2(textureSize(field, 0));
    vec2 a = (vec2(mod(low, grid.w), floor(low / grid.w)) * n.xz + at.xz) / size;
    vec2 b = (vec2(mod(high, grid.w), floor(high / grid.w)) * n.xz + at.xz) / size;
    return mix(textureLod(field, a, 0.0), textureLod(field, b, 0.0), at.y - 0.5 - low);
}

// What a block is to the gas: x 1 if it is solid, y its kind (see gas.rs), z its strength (1 is the usual).
// (In the map: red is the sky's light there, green the kind, plus 128 if solid, blue the strength, alpha the light of lamps and fires.)
vec4 gasBlock(sampler2D world, ivec3 block) {
    vec4 w = texelFetch(world, gasPixel(GasBlocks, clamp(block, ivec3(0), ivec3(GasBlocks.xyz) - 1)), 0);
    float k = floor(w.g * 255.0 + 0.5);
    float solid = step(127.5, k);
    return vec4(solid, k - 128.0 * solid, w.b * 2.0, 0.0);
}

float gasHash(uint x, uint y, uint z, uint t) {
    uint h = (x * 0x9E3779B1u) ^ (y * 0x85EBCA77u) ^ (z * 0xC2B2AE3Du) ^ (t * 0x27D4EB2Fu);
    h ^= h >> 15; h *= 0x2C1B3C6Du; h ^= h >> 12; h *= 0x297A2D39u; h ^= h >> 15;
    return float(h >> 8) / 16777216.0;
}

// A number between 0 and 1 for a place that changes smoothly in time, `rate` times a second.
float gasFlicker(uint x, uint y, uint z, float time, float rate) {
    float t = time * rate;
    float k = floor(t);
    float f = t - k;
    f = f * f * (3.0 - 2.0 * f);
    uint tick = uint(k);
    return mix(gasHash(x, y, z, tick), gasHash(x, y, z, tick + 1u), f);
}

// The same, changing smoothly from place to place as well: one lump per unit of x and z.
float gasLumps(float x, float z, float time, float rate) {
    float x0 = floor(x), z0 = floor(z);
    float tx = x - x0, tz = z - z0;
    tx = tx * tx * (3.0 - 2.0 * tx);
    tz = tz * tz * (3.0 - 2.0 * tz);
    uint xi = uint(int(x0)), zi = uint(int(z0));
    return mix(mix(gasFlicker(xi, 7u, zi, time, rate), gasFlicker(xi + 1u, 7u, zi, time, rate), tx),
               mix(gasFlicker(xi, 7u, zi + 1u, time, rate), gasFlicker(xi + 1u, 7u, zi + 1u, time, rate), tx), tz);
}

float gasLayer(float iy, float halfCell, float lo, float hi) {
    return clamp((min(iy + halfCell, hi) - max(iy - halfCell, lo)) / (2.0 * halfCell), 0.0, 1.0);
}

float gasDisc(float off, float halfCell, float r) {
    return clamp((r + halfCell - off) / (2.0 * halfCell), 0.0, 1.0);
}

// What a burning block of this kind puts into a fine cell: fuel and smoke per second, and the heat the cell is brought up to
// (gas.rs, `source`). `inside` is the middle of the cell inside the block, `halfCell` half its width, `place` where it is in the box (blocks).
vec3 gasSource(int kind, vec3 inside, float halfCell, vec3 place, float time, float live) {
    float off = length(inside.xz - 0.5);
    if (kind == 1) {
        float low = gasLayer(inside.y, halfCell, 0.0, 0.3);
        if (low <= 0.0) return vec3(0.0);
        float jet = clamp((gasLumps(place.x * 3.0, place.z * 3.0, time, 1.6) - 0.3) / 0.4, 0.0, 1.0);
        jet = jet * jet * (3.0 - 2.0 * jet);
        return vec3(11.0 * jet * live * low, 0.0, gasLayer(inside.y, halfCell, 0.0, 0.15) > 0.0 ? 0.5 * jet : 0.0);
    }
    if (kind == 2) {
        float cover = gasLayer(inside.y, halfCell, 0.15, 0.6) * gasDisc(off, halfCell, 0.3);
        if (cover <= 0.0) return vec3(0.0);
        float jet = clamp((gasLumps(place.x * 4.0, place.z * 4.0, time, 1.6) - 0.25) / 0.4, 0.0, 1.0);
        return vec3(11.0 * cover * jet * live, 2.2 * cover, gasLayer(inside.y, halfCell, 0.15, 0.35) > 0.0 ? 0.5 * gasDisc(off, halfCell, 0.3) * jet : 0.0);
    }
    if (kind == 3) {
        float cover = gasLayer(inside.y, halfCell, 0.6, 0.95) * gasDisc(off, halfCell, 0.12);
        return cover > 0.0 ? vec3(0.0, 0.7 * cover, 0.16) : vec3(0.0);
    }
    if (kind == 4) {
        float cover = gasLayer(inside.y, halfCell, 0.0, 0.3);
        return cover > 0.0 ? vec3(0.0, 0.15 * live * cover, 0.2 * live) : vec3(0.0);
    }
    if (kind == 5) {
        float cover = gasLayer(inside.y, halfCell, 0.0, 0.3) * gasDisc(off, halfCell, 0.26);
        return cover > 0.0 ? vec3(0.0, 5.0 * live * cover, 0.35) : vec3(0.0);
    }
    return vec3(0.0);
}

#endif
