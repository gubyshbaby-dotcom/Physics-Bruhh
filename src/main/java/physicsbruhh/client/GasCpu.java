package physicsbruhh.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import physicsbruhh.PhysicsBruhh;
import physicsbruhh.sim.GasParams;
import physicsbruhh.sim.NativeGas;

/**
 * The gas computed by the Rust core on a thread of its own, for the machine whose graphics card
 * cannot run the solver's passes. The game hands over the map of the world and the blobs as they
 * change; the thread steps about thirty times a second and leaves the grid as a picture, which the
 * drawing takes up as a texture and draws the same way as the other solver's.
 */
final class GasCpu {
    final GasBox box;
    private final NativeGas gas;
    private final Thread thread;
    private volatile boolean running = true;
    private final Object lock = new Object();

    // handed over by the game (under the lock)
    private final ByteBuffer world;
    private final ByteBuffer blobs = ByteBuffer.allocateDirect(GasDraw.BLOBS * GasParams.BLOB_FLOATS * 4).order(ByteOrder.nativeOrder());
    private int blobCount, ox, oy, oz;
    private boolean fed, awake;

    // left by the thread (under the lock)
    private final ByteBuffer picture;
    private int pictureX, pictureY, pictureZ, version;
    private float[] totals = new float[0];
    volatile float stepMs;
    volatile int steps;
    volatile boolean failed;

    // the render thread's own
    private final ByteBuffer upload;
    private GpuTexture texture;
    private GpuTextureView view;
    private int seen = -1, shownX, shownY, shownZ;

    GasCpu(GasBox box) {
        this.box = box;
        gas = new NativeGas(box.bx, box.by, box.bz, box.cpb, box.ratio);
        float[] params = GasParams.values();
        params[GasParams.ROUNDS] = box.rounds;
        gas.params(params);
        world = ByteBuffer.allocateDirect(box.bx * box.by * box.bz * 4).order(ByteOrder.nativeOrder());
        picture = ByteBuffer.allocateDirect(box.fineW * box.fineH * 4).order(ByteOrder.nativeOrder());
        upload = ByteBuffer.allocateDirect(box.fineW * box.fineH * 4).order(ByteOrder.nativeOrder());
        thread = new Thread(this::work, "Physics Bruhh gas");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    /** From the game: the map of the world in the box (four bytes a block: solid, kind, strength, light), the corner of the box, the blobs (places from that corner), and whether there is anything to compute. */
    void feed(byte[] map, int ox, int oy, int oz, float[] blobList, int count, boolean awake) {
        synchronized (lock) {
            world.clear();
            world.put(map, 0, Math.min(map.length, world.capacity()));
            this.ox = ox; this.oy = oy; this.oz = oz;
            blobCount = Math.min(count, GasDraw.BLOBS);
            blobs.clear();
            blobs.asFloatBuffer().put(blobList, 0, blobCount * GasParams.BLOB_FLOATS);
            fed = true;
            this.awake = awake;
        }
    }

    private void work() {
        ByteBuffer map = ByteBuffer.allocateDirect(world.capacity()).order(ByteOrder.nativeOrder());
        ByteBuffer balls = ByteBuffer.allocateDirect(blobs.capacity()).order(ByteOrder.nativeOrder());
        ByteBuffer out = ByteBuffer.allocateDirect(picture.capacity()).order(ByteOrder.nativeOrder());
        ByteBuffer sums = ByteBuffer.allocateDirect((4 + box.by) * 4).order(ByteOrder.nativeOrder());
        int x = 0, y = 0, z = 0;
        boolean placed = false;
        long last = System.nanoTime();
        try {
            while (running) {
                int count, nx, ny, nz;
                boolean go;
                synchronized (lock) {
                    go = fed && awake;
                    count = blobCount; nx = ox; ny = oy; nz = oz;
                    if (go) {
                        map.clear(); world.clear(); map.put(world);
                        balls.clear(); blobs.clear(); balls.put(blobs);
                    }
                }
                long now = System.nanoTime();
                if (!go) {
                    last = now;
                    Thread.sleep(20L);
                    continue;
                }
                float dt = Math.max(1.0F / 60.0F, Math.min(1.0F / 15.0F, (now - last) / 1.0e9F));
                last = now;
                if (!placed) { x = nx; y = ny; z = nz; placed = true; }
                if (!gas.step(dt, map, balls, count, nx - x, ny - y, nz - z)) throw new IllegalStateException("the core reported a failure in a gas step");
                x = nx; y = ny; z = nz;
                int written = gas.atlas(out, box.fineTiles);
                int n = gas.totals(sums);
                if (written < 0 || n < 0) throw new IllegalStateException("the core reported a failure handing the gas over");
                float[] t = new float[n];
                sums.asFloatBuffer().get(t);
                synchronized (lock) {
                    picture.clear(); out.clear(); picture.put(out);
                    pictureX = x; pictureY = y; pictureZ = z;
                    totals = t;
                    version++;
                }
                steps++;
                long spent = System.nanoTime() - now;
                stepMs = stepMs * 0.9F + spent / 1.0e6F * 0.1F;
                long rest = 33_000_000L - spent;
                if (rest > 1_000_000L) Thread.sleep(rest / 1_000_000L);
            }
        } catch (InterruptedException e) {
            // closing
        } catch (Throwable t) {
            failed = true;
            PhysicsBruhh.LOGGER.error("The gas solver of the Rust core failed and is switched off", t);
        }
    }

    /** On the render thread: takes up what the thread has left since last time. Returns false until there has been something. */
    boolean take(CommandEncoder encoder) {
        boolean fresh = false;
        synchronized (lock) {
            if (version != seen) {
                seen = version;
                upload.clear(); picture.clear(); upload.put(picture);
                shownX = pictureX; shownY = pictureY; shownZ = pictureZ;
                fresh = true;
            }
        }
        if (fresh) {
            if (texture == null) {
                texture = RenderSystem.getDevice().createTexture(() -> "Gas: smoke, heat, fuel from the processor", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, box.fineW, box.fineH, 1, 1);
                view = RenderSystem.getDevice().createTextureView(texture);
            }
            upload.position(0).limit(upload.capacity());
            encoder.writeToTexture(texture, upload, 0, 0, 0, 0, box.fineW, box.fineH);
        }
        return fresh;
    }

    GpuTextureView gas() { return view; }

    /** The corner of the box the picture last taken up was computed for. */
    int shownX() { return shownX; }
    int shownY() { return shownY; }
    int shownZ() { return shownZ; }

    /** Smoke in all, heat in all, the hottest cell, the fastest cell, then the smoke in each layer of blocks. */
    float[] totals() {
        synchronized (lock) { return totals; }
    }

    void close() {
        running = false;
        thread.interrupt();
        try { thread.join(2000L); } catch (InterruptedException ignored) { }
        if (!thread.isAlive()) gas.close();
        if (view != null) view.close();
        if (texture != null) texture.close();
        view = null; texture = null;
    }
}
