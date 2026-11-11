package physicsbruhh.sim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The gas solver of the Rust core ({@code native/src/gas.rs}): what smoke and fire are computed
 * with where the graphics card cannot do it. All buffers are direct and in native byte order.
 */
public final class NativeGas implements AutoCloseable {
    private static final int ERR = -9999;
    private long handle;
    public final int bx, by, bz, cpb, ratio;

    /** A box of this many blocks, with {@code cpb} coarse cells to the block and {@code ratio} fine cells to the coarse one. */
    public NativeGas(int bx, int by, int bz, int cpb, int ratio) {
        if (!NativeSim.available()) throw new IllegalStateException("native core unavailable: " + NativeSim.loadMessage());
        handle = nCreate(bx, by, bz, cpb, ratio);
        if (handle == 0) throw new IllegalStateException("native core failed to create a gas box of " + bx + "x" + by + "x" + bz);
        this.bx = bx; this.by = by; this.bz = bz; this.cpb = cpb; this.ratio = ratio;
    }

    /** The core's own copy of the model's numbers. */
    public static float[] defaults() {
        if (!NativeSim.available()) return new float[0];
        ByteBuffer out = ByteBuffer.allocateDirect(GasParams.COUNT * 4 + 64).order(ByteOrder.nativeOrder());
        int n = nDefaults(out);
        if (n <= 0) return new float[0];
        float[] values = new float[n];
        out.asFloatBuffer().get(values);
        return values;
    }

    public boolean params(float[] values) {
        ByteBuffer in = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        in.asFloatBuffer().put(values);
        return nParams(handle, in) == 0;
    }

    /** One step. {@code world}: four bytes a block (solid, kind, strength, light), x fastest, then z, then y. */
    public boolean step(float dt, ByteBuffer world, ByteBuffer blobs, int blobCount, int dx, int dy, int dz) {
        return nStep(handle, dt, world, blobs, blobCount, dx, dy, dz) == 0;
    }

    /** The fine grid as a picture, {@code tiles} layers to a row, four bytes a cell. Returns the bytes written, or less than zero. */
    public int atlas(ByteBuffer out, int tiles) {
        return nAtlas(handle, out, tiles);
    }

    /** Smoke in all, heat in all, the hottest cell, the fastest cell, then the smoke in each layer of blocks from the bottom up. */
    public int totals(ByteBuffer out) {
        return nTotals(handle, out);
    }

    public void clear() {
        nClear(handle);
    }

    @Override
    public void close() {
        if (handle != 0) { nDestroy(handle); handle = 0; }
    }

    private static native long nCreate(int bx, int by, int bz, int cpb, int ratio);
    private static native void nDestroy(long h);
    private static native int nDefaults(ByteBuffer out);
    private static native int nParams(long h, ByteBuffer values);
    private static native int nStep(long h, float dt, ByteBuffer world, ByteBuffer blobs, int blobCount, int dx, int dy, int dz);
    private static native int nAtlas(long h, ByteBuffer out, int tiles);
    private static native int nTotals(long h, ByteBuffer out);
    private static native int nClear(long h);
}
