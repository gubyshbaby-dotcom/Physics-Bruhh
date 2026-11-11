package physicsbruhh.client;

/**
 * The box of the world the gas is computed in, and how its grids lie in their textures.
 *
 * <p>Two grids over the same box ({@code native/src/gas.rs} says why): a coarse one for velocity and
 * pressure, {@code cpb} cells to the block, and one {@code ratio} times finer for smoke, heat and
 * fuel. A grid lies in its texture as layers of constant height side by side, {@code tiles} to a
 * row: cell (x, y, z) is the pixel (x + (y mod tiles) * nx, z + (y / tiles) * nz). The map of the
 * world in the box lies the same way, one pixel a block.
 */
final class GasBox {
    /** Blocks. */
    final int bx, by, bz;
    final int cpb, ratio;
    /** Rounds of the pressure solve a step. */
    final int rounds;
    /** Coarse cells, fine cells. */
    final int nx, ny, nz, fx, fy, fz;
    final int coarseTiles, fineTiles, blockTiles;
    final int coarseW, coarseH, fineW, fineH, blockW, blockH;

    GasBox(int bx, int by, int bz, int cpb, int ratio, int rounds) {
        this.bx = bx; this.by = by; this.bz = bz; this.cpb = cpb; this.ratio = ratio;
        this.rounds = Math.max(2, rounds + 1 & ~1);
        nx = bx * cpb; ny = by * cpb; nz = bz * cpb;
        fx = nx * ratio; fy = ny * ratio; fz = nz * ratio;
        coarseTiles = tiles(nx, ny, nz); fineTiles = tiles(fx, fy, fz); blockTiles = tiles(bx, by, bz);
        coarseW = coarseTiles * nx; coarseH = rows(ny, coarseTiles) * nz;
        fineW = fineTiles * fx; fineH = rows(fy, fineTiles) * fz;
        blockW = blockTiles * bx; blockH = rows(by, blockTiles) * bz;
    }

    /** Layers to a row that make the texture about as wide as it is high. */
    private static int tiles(int nx, int ny, int nz) {
        return Math.max(1, (int) Math.round(Math.sqrt((double) ny * nz / nx)));
    }

    private static int rows(int layers, int tiles) {
        return (layers + tiles - 1) / tiles;
    }

    boolean same(GasBox other) {
        return other != null && bx == other.bx && by == other.by && bz == other.bz && cpb == other.cpb && ratio == other.ratio && rounds == other.rounds;
    }

    /** Where a block of the box is in the picture of the map (index of its first byte). */
    int blockPixel(int x, int y, int z) {
        return ((z + (y / blockTiles) * bz) * blockW + x + (y % blockTiles) * bx) * 4;
    }

    long fineCells() {
        return (long) fx * fy * fz;
    }

    @Override
    public String toString() {
        return bx + "x" + by + "x" + bz + " blocks, " + fx + "x" + fy + "x" + fz + " cells";
    }
}
