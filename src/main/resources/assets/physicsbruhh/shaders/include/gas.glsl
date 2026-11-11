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
    vec4 GasFrom;     // the part of the box that is computed this step, in blocks: its least corner ...
    vec4 GasTo;       // ... and the corner just past its greatest (the rest of the box is empty and is left alone)
    vec4 GasUnder[3]; // the grids under the coarse one that the pressure is solved on along the way, each twice as coarse as the one before: cells along x, y, z (of the whole box), tiles to a row
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

// ---- the part of the box that is computed. Gas is seldom everywhere: the solver's passes draw only over the cells of
// a smaller box round what there is, and to them that box is the world: beyond it there is nothing to read.

// The least and the greatest cell of a grid that are computed this step.
ivec3 gasLow(vec4 grid) {
    return ivec3(GasFrom.xyz) * int(grid.x / GasBlocks.x);
}

ivec3 gasHigh(vec4 grid) {
    return ivec3(GasTo.xyz) * int(grid.x / GasBlocks.x) - 1;
}

bool gasComputed(vec4 grid, ivec3 cell) {
    return all(greaterThanEqual(cell, gasLow(grid))) && all(lessThanEqual(cell, gasHigh(grid)));
}

// A cell's own value as this step leaves it; cells that are not computed answer with the nearest one that is.
vec4 gasFetchNear(sampler2D field, vec4 grid, ivec3 cell) {
    return texelFetch(field, gasPixel(grid, clamp(cell, gasLow(grid), gasHigh(grid))), 0);
}

// The same for a value between cells.
vec4 gasAtNear(sampler2D field, vec4 grid, vec3 at) {
    vec3 n = grid.xyz;
    ivec3 lo = gasLow(grid), hi = gasHigh(grid);
    at = clamp(at, vec3(lo) + 0.5, vec3(hi) + 0.5);
    float low = floor(at.y - 0.5);
    float high = min(low + 1.0, float(hi.y));
    vec2 size = vec2(textureSize(field, 0));
    vec2 a = (vec2(mod(low, grid.w), floor(low / grid.w)) * n.xz + at.xz) / size;
    vec2 b = (vec2(mod(high, grid.w), floor(high / grid.w)) * n.xz + at.xz) / size;
    return mix(textureLod(field, a, 0.0), textureLod(field, b, 0.0), at.y - 0.5 - low);
}

// What the last step left, which was written before the box last moved: the same places, counted from where the box was.
vec4 gasFetchWas(sampler2D field, vec4 grid, ivec3 cell) {
    ivec3 moved = ivec3(GasMoved.xyz) * int(grid.x / GasBlocks.x);
    return gasFetch(field, grid, clamp(cell, gasLow(grid), gasHigh(grid)) + moved);
}

vec4 gasAtWas(sampler2D field, vec4 grid, vec3 at) {
    return gasAt(field, grid, clamp(at, vec3(gasLow(grid)) + 0.5, vec3(gasHigh(grid)) + 0.5) + GasMoved.xyz * (grid.x / GasBlocks.x));
}

// ---- the pressure: solved on the coarse grid (level 0) and on three grids under it (levels 1 to 3), each twice as
// coarse as the one before (Gas::pressure in gas.rs says why). A pass works on the level its pipeline names. The
// grids under the coarse one start at the corner of the part of the box that is computed: their cell (0, 0, 0)
// covers the first two cells each way of the level above.

#if defined(LEVEL3)
#define GAS_LEVEL 3
#elif defined(LEVEL2)
#define GAS_LEVEL 2
#elif defined(LEVEL1)
#define GAS_LEVEL 1
#else
#define GAS_LEVEL 0
#endif

// How much of a full round the rounds of the pressure solve take (ROUND_WEIGHT in gas.rs).
const float GAS_ROUND_WEIGHT = 6.0 / 7.0;

// A level's grid as it lies in its texture.
vec4 gasLevelGrid(int level) {
    return level == 0 ? GasCoarse : GasUnder[level - 1];
}

// The first cell of a level that is in use, and how many are in use along x, y, z.
ivec3 gasLevelFrom(int level) {
    return level == 0 ? gasLow(GasCoarse) : ivec3(0);
}

ivec3 gasLevelCells(int level) {
    ivec3 n = gasHigh(GasCoarse) - gasLow(GasCoarse) + 1;
    return (n + (1 << level) - 1) >> level;
}

// How strongly a whole cell at the edge of what is computed is tied to the zero beyond it: that zero is one coarse
// cell beyond the last, which for a grid `size` times as coarse is nearer than its own cells are to each other.
// (gas_up.fsh lets a level's answer fall off to that zero by it.)
float gasLevelEdge(int level) {
    float size = float(1 << level);
    return 2.0 * size / (size + 1.0);
}

// The square of the size of a level's cell, in blocks.
float gasLevelCell2(int level) {
    float size = float(1 << level) / GasStep.z;
    return size * size;
}

// x: the pressures of a cell's six neighbours, each times how strongly the cell is tied to it, summed; y: the sum of
// the ties. A block is not tied to (nothing is pushed into it); beyond the cells in use the pressure is zero.
//
// How strong a tie is goes by what the cells really cover (Shape::tie in gas.rs): where the part that is computed
// does not divide evenly, the last cell of a level under the coarse grid sticks out past the edge and covers fewer
// of the coarse grid's cells than the rest; and the zero beyond the edge is one coarse cell out, which for a cell of
// such a level is nearer than its own neighbours are. A tie is the face two cells share (as a share of a whole face)
// over how far apart their middles are (in cells): 1 between two whole cells, and on the coarse grid itself always.
// `cell` is counted from the first cell in use.
vec2 gasTies(sampler2D pressure, int level, ivec3 cell) {
    vec4 grid = gasLevelGrid(level);
    ivec3 from = gasLevelFrom(level), cells = gasLevelCells(level);
    int size = 1 << level;
    float s = float(size);
    // how many of the coarse grid's cells this one covers along each axis
    ivec3 all = gasHigh(GasCoarse) - gasLow(GasCoarse) + 1;
    vec3 here = vec3(min(all - cell * size, ivec3(size)));
    vec2 ties = vec2(0.0);
    for (int i = 0; i < 6; i++) {
        int axis = i >> 1;
        ivec3 n = cell;
        n[axis] += (i & 1) == 0 ? 1 : -1;
        float face = here[(axis + 1) % 3] * here[(axis + 2) % 3] / (s * s);
        if (any(lessThan(n, ivec3(0))) || any(greaterThanEqual(n, cells))) { ties.y += face * 2.0 * s / (here[axis] + 1.0); continue; }
        float v = texelFetch(pressure, gasPixel(grid, from + n), 0).r;
        if (v > GAS_WALL_TEST) {
            float there = float(min(all[axis] - n[axis] * size, size));
            float tie = face * 2.0 * s / (here[axis] + there);
            ties += vec2(v * tie, tie);
        }
    }
    return ties;
}

// What a block is to the gas: x 1 if it is solid, y its kind (see gas.rs), z its strength (1 is the usual).
// (In the map: red is the sky's light there, green the kind, plus 128 if solid, blue the strength, alpha the light of lamps and fires.)
vec4 gasBlock(sampler2D world, ivec3 block) {
    vec4 w = texelFetch(world, gasPixel(GasBlocks, clamp(block, ivec3(0), ivec3(GasBlocks.xyz) - 1)), 0);
    float k = floor(w.g * 255.0 + 0.5);
    float solid = step(127.5, k);
    return vec4(solid, k - 128.0 * solid, w.b * 2.0, 0.0);
}

// ---- where nothing can happen. Even in the part of the box that is computed most of the air holds nothing. A
// block is awake this step if something feeds the gas in it, or if gas was seen in it or in one of the six blocks
// next to it after the last step (`info`: gas_where.fsh, written before the box last moved; it counts one cell into
// the blocks around, so gas about to cross an edge or a corner is seen from both sides). Gas does not get further
// than a block in a step. The cells of a block that is not awake hold nothing and are not worked on: what little may
// have been left in them is too thin to be seen.
//
// `block`: what gasBlock says of it, `inBlock`: which block, `place`: a place in it, in blocks, and `around`: how far
// from that place what is asked about reaches (0 for a cell's middle).
bool gasAwake(sampler2D info, vec4 block, ivec3 inBlock, vec3 place, float around) {
    if (block.g > 0.5) return true;
    int blobs = int(GasMoved.w);
    for (int i = 0; i < blobs; i++) {
        vec4 ball = GasBlob[i * 3];
        // (an explosion throws its gas a block further than it is wide before it is seen there)
        float reach = ball.w + (GasBlob[i * 3 + 2].w != 0.0 ? 1.0 : 0.0) + around;
        vec3 d = place - ball.xyz;
        if (dot(d, d) < reach * reach) return true;
    }
    ivec3 was = inBlock + ivec3(GasMoved.xyz);
    ivec3 n = ivec3(GasBlocks.xyz);
    for (int i = 0; i < 7; i++) {
        ivec3 b = was;
        if (i > 0) b[(i - 1) >> 1] += (i & 1) == 1 ? 1 : -1;
        if (any(lessThan(b, ivec3(0))) || any(greaterThanEqual(b, n))) continue;
        vec2 seen = texelFetch(info, gasPixel(GasBlocks, b), 0).rg;
        if (seen.r > 0.0 || seen.g > 0.0) return true;
    }
    return false;
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
