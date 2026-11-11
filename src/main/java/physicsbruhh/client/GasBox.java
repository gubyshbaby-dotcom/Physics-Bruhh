package physicsbruhh.client;

/**
 * The box of the world the gas is computed in, and how its grids lie in their textures.
 *
 * <p>Two grids over the same box ({@code native/src/gas.rs} says why): a coarse one for velocity and
 * pressure, {@code cpb} cells to the block, and one {@code ratio} times finer for smoke, heat and
 * fuel. A grid lies in its texture as layers of constant height side by side, {@code tiles} to a
 * row: cell (x, y, z) is the pixel (x + (y mod tiles) * nx, z + (y / tiles) * nz). The map of the
 * world in the box lies the same way, one pixel a block.
 *
 * <p>Under the coarse grid there are {@link #UNDER} more for the pressure solve, each twice as
 * coarse as the one before ({@code Gas::pressure} in gas.rs says what for), laid out the same way.
 */
final class GasBox {
    /** Blocks. */
    final int bx, by, bz;
    final int cpb, ratio;
    /** Rounds of the pressure solve on the coarse grid each step: half before the grids under it are asked, half after; from 8 on the whole thing is done {@code rounds / 4} times over. */
    final int rounds;
    /** Grids under the coarse one that the pressure is solved on. */
    static final int UNDER = 3;
    /** Those grids: cells along x, y, z, layers to a row of the texture, the size of the texture. */
    final int[] underX = new int[UNDER], underY = new int[UNDER], underZ = new int[UNDER], underTiles = new int[UNDER], underW = new int[UNDER], underH = new int[UNDER];
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
        int ux = nx, uy = ny, uz = nz;
        for (int level = 0; level < UNDER; level++) {
            ux = (ux + 1) / 2; uy = (uy + 1) / 2; uz = (uz + 1) / 2;
            underX[level] = ux; underY[level] = uy; underZ[level] = uz;
            underTiles[level] = tiles(ux, uy, uz);
            underW[level] = underTiles[level] * ux; underH[level] = rows(uy, underTiles[level]) * uz;
        }
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
