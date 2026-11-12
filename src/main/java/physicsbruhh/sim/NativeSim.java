package physicsbruhh.sim;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Rust core through JNI. Use {@link #available()} before constructing. */
public final class NativeSim implements GooSim {
    public static final int ABI_VERSION = 8;
    private static final int ERR = -9999;
    private static Boolean loaded;
    private static String loadMessage = "not tried";

    private long handle;
    private boolean broken;

    public NativeSim(int particlesPerEdge, int maxBodies) {
        if (!available()) throw new IllegalStateException("native core unavailable: " + loadMessage);
        handle = nCreate(particlesPerEdge, maxBodies);
        if (handle == 0) throw new IllegalStateException("native core failed to create a world");
    }

    /** Extracts the library for this platform from the jar and loads it. Safe to call repeatedly. */
    public static synchronized boolean available() {
        if (loaded != null) return loaded;
        loaded = false;
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            String o = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
            String a = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x86_64";
            String lib = o.equals("windows") ? "physicsbruhh_native.dll" : o.equals("macos") ? "libphysicsbruhh_native.dylib" : "libphysicsbruhh_native.so";
            String resource = "/natives/" + o + "-" + a + "/" + lib;
            try (InputStream in = NativeSim.class.getResourceAsStream(resource)) {
                if (in == null) { loadMessage = "no bundled library at " + resource; return false; }
                Path dir = Files.createTempDirectory("physicsbruhh");
                Path file = dir.resolve(lib);
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                file.toFile().deleteOnExit();
                dir.toFile().deleteOnExit();
                System.load(file.toAbsolutePath().toString());
            }
            int v = nVersion();
            if (v != ABI_VERSION) { loadMessage = "library ABI " + v + ", expected " + ABI_VERSION; return false; }
            loaded = true;
            loadMessage = "loaded " + resource;
        } catch (Throwable t) {
            loadMessage = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return loaded;
    }

    public static String loadMessage() { return loadMessage; }

    /** True once any call reported an internal failure; the owner should replace this sim. */
    public boolean broken() { return broken; }

    private int check(int r) { if (r == ERR) broken = true; return r; }

    @Override public int addBody(int material, float x, float y, float z, float dirX, float dirZ, float age, float thick) { return check(nAddBody(handle, material, x, y, z, dirX, dirZ, age, thick)); }
    @Override public int addEmpty(int material, float x, float y, float z, float thick) { return check(nAddEmpty(handle, material, x, y, z, thick)); }
    @Override public int addResting(int material, float x, float y, float z, int count, float thick) { return check(nAddResting(handle, material, x, y, z, count, thick)); }
    @Override public int emit(int body, float px, float py, float pz, float vx, float vy, float vz, int count, boolean lump) { return check(nEmit(handle, body, px, py, pz, vx, vy, vz, count, lump)); }
    @Override public int take(int body, float x, float y, float z, int count) { return check(nTake(handle, body, x, y, z, count)); }
    @Override public void removeBody(int body, boolean fade) { check(nRemoveBody(handle, body, fade)); }
    @Override public void wake(int body) { check(nWake(handle, body)); }
    @Override public void setKillY(float y) { check(nSetKillY(handle, y)); }
    @Override public void setStatic(ByteBuffer boxes, int count) { check(nSetStatic(handle, boxes, count)); }
    @Override public void setKinematic(ByteBuffer boxes, int count) { check(nSetKinematic(handle, boxes, count)); }
    @Override public int step(float dt) { return check(nStep(handle, dt)); }
    @Override public int mesh(int material, ByteBuffer out) { return check(nMesh(handle, material, out)); }
    @Override public int meshParts(int material, ByteBuffer out) { return check(nMeshParts(handle, material, out)); }
    @Override public int bodies(ByteBuffer out) { return check(nBodies(handle, out)); }
    @Override public int particles(ByteBuffer out) { return check(nParticles(handle, out)); }
    @Override public int probe(float x0, float y0, float z0, float x1, float y1, float z1) { return check(nProbe(handle, x0, y0, z0, x1, y1, z1)); }
    @Override public int count(int what, int arg) { return check(nCount(handle, what, arg)); }
    @Override public String backend() { return "rust"; }
    @Override public void close() { if (handle != 0) { nDestroy(handle); handle = 0; } }

    private static native int nVersion();
    private static native long nCreate(int n, int maxBodies);
    private static native void nDestroy(long h);
    private static native int nAddBody(long h, int mat, float x, float y, float z, float dirX, float dirZ, float age, float thick);
    private static native int nAddEmpty(long h, int mat, float x, float y, float z, float thick);
    private static native int nAddResting(long h, int mat, float x, float y, float z, int count, float thick);
    private static native int nEmit(long h, int body, float px, float py, float pz, float vx, float vy, float vz, int count, boolean lump);
    private static native int nTake(long h, int body, float x, float y, float z, int count);
    private static native int nRemoveBody(long h, int body, boolean fade);
    private static native int nWake(long h, int body);
    private static native int nSetKillY(long h, float y);
    private static native int nSetStatic(long h, ByteBuffer buf, int count);
    private static native int nSetKinematic(long h, ByteBuffer buf, int count);
    private static native int nStep(long h, float dt);
    private static native int nMesh(long h, int mat, ByteBuffer buf);
    private static native int nMeshParts(long h, int mat, ByteBuffer buf);
    private static native int nBodies(long h, ByteBuffer buf);
    private static native int nParticles(long h, ByteBuffer buf);
    private static native int nProbe(long h, float x0, float y0, float z0, float x1, float y1, float z1);
    private static native int nCount(long h, int what, int arg);
}
