package physicsbruhh.sim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Stand-alone check (no Minecraft, no test framework): runs the same scenarios through the Java
 * simulation and, when the native library is on the classpath, through the Rust one, asserts the
 * behaviour the mod relies on and that the two agree. Run: {@code java physicsbruhh.sim.SimCheck}.
 */
public final class SimCheck {
    static int failures;

    static ByteBuffer buf(int floats) { return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()); }

    static ByteBuffer floor(float[]... extra) {
        ByteBuffer b = buf((11 * 7 + extra.length) * 7); FloatBuffer f = b.asFloatBuffer();
        for (int x = -5; x < 6; x++) for (int z = -3; z < 4; z++) f.put(new float[] { x, -1, z, x + 1, 0, z + 1, 1 });
        for (float[] e : extra) f.put(e);
        return b;
    }

    static int floorCount(int extra) { return 11 * 7 + extra; }

    static ByteBuffer plates(float[]... p) { ByteBuffer b = buf(p.length * 11); FloatBuffer f = b.asFloatBuffer(); for (float[] x : p) f.put(x); return b; }

    static float[] plate(int id, float face, float dir, float vel, float stick) {
        float a = dir > 0 ? face - 0.25f : face, b = dir > 0 ? face : face + 0.25f;
        return new float[] { id, a, 0, 0, b, 1, 1, vel, 0, 0, stick };
    }

    static void run(GooSim s, float secs) { for (int i = 0; i < (int) (secs * 60); i++) s.step(1f / 60f + 1e-6f); }

    static float[] body(GooSim s, int b) { ByteBuffer o = buf(64 * 8); s.bodies(o); float[] r = new float[8]; o.asFloatBuffer().get(b * 8, r, 0, 8); return r; }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what + "  [" + detail + "]");
        if (!ok) failures++;
    }

    interface Factory { GooSim make(int n, int bodies); }

    static float[] scenarios(String name, Factory f) {
        System.out.println(name + ":");
        float[] sig = new float[6];
        long t0 = System.nanoTime();
        try (GooSim s = f.make(12, 4)) {
            s.setStatic(floor(), floorCount(0));
            s.addBody(GooSim.HONEY, 0, 0, 0, 1, 0, 0);
            run(s, 1f);
            check("honey still block-like after 1 s", body(s, 0)[6] > 0.7f, "top " + body(s, 0)[6]);
            run(s, 9f);
            float[] b = body(s, 0);
            check("honey flattened", b[6] < 0.45f, "top " + b[6]);
            check("honey spread", b[5] - b[2] > 1.5f, "width " + (b[5] - b[2]));
            sig[0] = b[6]; sig[1] = b[5] - b[2];
            ByteBuffer m = buf(1 << 20);
            int quads = s.mesh(GooSim.HONEY, m);
            check("honey mesh", quads > 500 && quads < 20000, quads + " quads");
            check("mesh buffer too small is reported", s.mesh(GooSim.HONEY, buf(24)) < 0, "");
            run(s, 25f);
            check("settled honey sleeps", body(s, 0)[1] == 0f, "awake " + body(s, 0)[1]);
        }
        try (GooSim s = f.make(12, 4)) {
            s.setStatic(floor(new float[] { -2, 0, 0, -1, 1, 1, 1 }), floorCount(1));
            s.addBody(GooSim.SLIME, -1, 0, 0, 1, 0, 0);
            run(s, 1f);
            float[] b0 = body(s, 0);
            check("slime keeps its shape", b0[6] > 0.75f && b0[6] < 1.05f, "top " + b0[6]);
            float c0 = (b0[2] + b0[5]) * 0.5f;
            for (int k = 0; k < 6; k++) { s.setKinematic(plates(plate(1, -1f + k / 6f, 1, 10, 3)), 1); s.step(1f / 60f + 1e-6f); }
            for (int k = 0; k < 90; k++) { s.setKinematic(plates(plate(1, 0, 1, 0, 3)), 1); s.step(1f / 60f + 1e-6f); }
            float[] b1 = body(s, 0); float c1 = (b1[2] + b1[5]) * 0.5f;
            check("piston pushes slime about a block", c1 - c0 > 0.5f && c1 - c0 < 1.5f, "moved " + (c1 - c0));
            check("slime not launched", b1[5] < 2.4f, "max x " + b1[5]);
            for (int k = 0; k < 6; k++) { s.setKinematic(plates(plate(1, -k / 6f, 1, -10, 3)), 1); s.step(1f / 60f + 1e-6f); }
            ByteBuffer m = buf(1 << 20);
            int stretched = s.mesh(GooSim.SLIME, m);
            for (int k = 0; k < 120; k++) { s.setKinematic(plates(plate(1, -1, 1, 0, 3)), 1); s.step(1f / 60f + 1e-6f); }
            float[] b2 = body(s, 0); float c2 = (b2[2] + b2[5]) * 0.5f;
            check("sticky piston drags slime back", c1 - c2 > 0.4f, "moved back " + (c1 - c2));
            int relaxed = s.mesh(GooSim.SLIME, m);
            check("stretched slime has more surface", stretched > relaxed + 100, stretched + " vs " + relaxed + " quads");
            sig[2] = c1 - c0; sig[3] = c1 - c2;
        }
        try (GooSim s = f.make(10, 2)) {
            s.setStatic(floor(), floorCount(0));
            s.addBody(GooSim.HONEY, 0, 0, 0, 1, 0, 100f);
            run(s, 2f);
            check("old honey spawns as a puddle", body(s, 0)[6] < 0.45f, "top " + body(s, 0)[6]);
            s.addBody(GooSim.SLIME, 3, 0, 0, 1, 0, 0);
            int third = s.addBody(GooSim.SLIME, -3, 0, 0, 1, 0, 0);
            check("full world recycles a slot", third == 0 || third == 1, "slot " + third);
            s.removeBody(third, false);
            check("removed body is free", body(s, third)[0] == 0f, "");
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        try (GooSim s = f.make(12, 4)) {
            s.setStatic(floor(), floorCount(0));
            s.addBody(GooSim.HONEY, 0, 0, 0, 1, 0, 0);
            run(s, 2f);
            long a = System.nanoTime(); run(s, 3f); sig[4] = (float) ((System.nanoTime() - a) / 1e6 / 180.0);
            ByteBuffer m = buf(1 << 20); a = System.nanoTime(); for (int k = 0; k < 20; k++) s.mesh(GooSim.HONEY, m); sig[5] = (float) ((System.nanoTime() - a) / 1e6 / 20.0);
        }
        System.out.printf("  %.0f ms total, %.2f ms per step, %.2f ms per mesh (one honey block)%n", ms, sig[4], sig[5]);
        return sig;
    }

    public static void main(String[] args) {
        float[] j = scenarios("Java simulation", JavaSim::new);
        if (NativeSim.available()) {
            float[] r = scenarios("Rust simulation (" + NativeSim.loadMessage() + ")", NativeSim::new);
            System.out.println("Java vs Rust:");
            check("puddle height agrees", Math.abs(j[0] - r[0]) < 0.12f, j[0] + " vs " + r[0]);
            check("puddle width agrees", Math.abs(j[1] - r[1]) < 0.35f, j[1] + " vs " + r[1]);
            check("push distance agrees", Math.abs(j[2] - r[2]) < 0.35f, j[2] + " vs " + r[2]);
            check("pull distance agrees", Math.abs(j[3] - r[3]) < 0.45f, j[3] + " vs " + r[3]);
        } else {
            System.out.println("Rust simulation skipped: " + NativeSim.loadMessage());
            if (args.length > 0 && args[0].equals("--require-native")) { System.out.println("  FAIL  native library required"); failures++; }
        }
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
