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

    static ByteBuffer plates(float[]... p) { ByteBuffer b = buf(p.length * GooSim.KINEMATIC_FLOATS); FloatBuffer f = b.asFloatBuffer(); for (float[] x : p) f.put(x); return b; }

    static float[] plate(int id, float face, float dir, float vel, float stick) {
        float a = dir > 0 ? face - 0.25f : face, b = dir > 0 ? face : face + 0.25f;
        return new float[] { id, a, 0, 0, b, 1, 1, vel, 0, 0, stick, dir > 0 ? 1 : 2 };
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
        float[] sig = new float[10];
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
            int in = s.probe(-3f, 0f, -3f, 4f, 2f, 4f), out = s.probe(20f, 0f, 20f, 21f, 2f, 21f);
            check("probe finds the honey and only where it is", (in & 0xFFFF) > 1000 && (in >>> 16) == 0 && out == 0, in + " / " + out);
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
            for (int k = 0; k < 6; k++) { s.setKinematic(plates(plate(1, -1f + k / 6f, 1, 10, 8)), 1); s.step(1f / 60f + 1e-6f); }
            for (int k = 0; k < 90; k++) { s.setKinematic(plates(plate(1, 0, 1, 0, 8)), 1); s.step(1f / 60f + 1e-6f); }
            float[] b1 = body(s, 0); float c1 = (b1[2] + b1[5]) * 0.5f;
            check("piston pushes slime about a block", c1 - c0 > 0.5f && c1 - c0 < 1.5f, "moved " + (c1 - c0));
            check("slime not launched", b1[5] < 2.4f, "max x " + b1[5]);
            for (int k = 0; k < 6; k++) { s.setKinematic(plates(plate(1, -k / 6f, 1, -10, 8)), 1); s.step(1f / 60f + 1e-6f); }
            ByteBuffer m = buf(1 << 20);
            int stretched = s.mesh(GooSim.SLIME, m);
            for (int k = 0; k < 120; k++) { s.setKinematic(plates(plate(1, -1, 1, 0, 8)), 1); s.step(1f / 60f + 1e-6f); }
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
        try (GooSim s = f.make(12, 4)) {
            // a bottle of honey squirted from one block up, then slime balls thrown the same way
            s.setStatic(floor(), floorCount(0));
            int honey = s.addEmpty(GooSim.HONEY, 0, 1, 0);
            check("a bottle fits into an empty piece", s.emit(honey, 0f, 1.5f, 0.5f, 3.5f, 0f, 0f, GooSim.BOTTLE, false) == GooSim.BOTTLE, "");
            check("all of it counts at once", s.count(4, honey) == GooSim.BOTTLE, "amount " + s.count(4, honey));
            run(s, 0.25f);
            int out = s.probe(-9f, -1f, -9f, 9f, 9f, 9f) & 0xFFFF;
            check("the stream comes out bit by bit", out > 40 && out < GooSim.BOTTLE, out + " particles after a quarter of a second");
            run(s, 7f);
            float[] b = body(s, honey);
            check("the stream became a flat puddle ahead", b[6] < 0.45f && b[2] > 0.2f && b[5] < 4.5f, "top " + b[6] + ", x " + b[2] + ".." + b[5]);
            check("every particle came out", (s.probe(-9f, -1f, -9f, 9f, 9f, 9f) & 0xFFFF) == GooSim.BOTTLE, "");
            sig[6] = (b[2] + b[5]) * 0.5f; sig[7] = b[5] - b[2];
            float midZ = (b[4] + b[7]) * 0.5f;
            check("a dipped bottle takes a bottle's worth", s.take(honey, b[2], 0.1f, midZ, GooSim.BOTTLE / 2) == GooSim.BOTTLE / 2 && s.count(4, honey) == GooSim.BOTTLE / 2, "left " + s.count(4, honey));
            check("no more can be taken than there is", s.take(honey, b[2], 0.1f, midZ, 5000) == GooSim.BOTTLE / 2, "");
            run(s, 1f);
            check("an emptied piece is gone", body(s, honey)[0] == 0f, "state " + body(s, honey)[0]);
            int slime = s.addEmpty(GooSim.SLIME, 0, 1, 0);
            for (int k = 0; k < 3; k++) { s.emit(slime, 0.3f, 1.5f, 0.5f, 4f, 0.5f, 0f, GooSim.BALL, true); run(s, 0.45f); }
            run(s, 6f);
            float[] c = body(s, slime);
            check("three slime balls make one heap ahead", c[5] - c[2] < 1.6f && c[7] - c[4] < 1.6f && c[6] < 0.9f && c[2] > 0.3f, "x " + c[2] + ".." + c[5] + ", top " + c[6]);
            check("the heap has all three balls", (s.probe(-9f, -1f, -9f, 9f, 9f, 9f) >>> 16) == 3 * GooSim.BALL, "");
            check("the heap comes to rest", c[1] == 0f, "awake " + c[1]);
            sig[8] = (c[2] + c[5]) * 0.5f;
            int back = s.addResting(GooSim.HONEY, 4, 0, -2, 2 * GooSim.BOTTLE);
            run(s, 3f);
            float[] d = body(s, back);
            check("goo seen again lies at rest", d[6] < 0.4f && s.count(4, back) == 2 * GooSim.BOTTLE, "top " + d[6]);
            // what the liquid look is drawn from: every particle once, slime marked as slime, all inside the bounds of the goo
            ByteBuffer small = buf(8);
            int need = s.particles(small);
            ByteBuffer all = buf(Math.max(8, -need));
            int count = s.particles(all);
            FloatBuffer pf = all.asFloatBuffer();
            int honeyParticles = 0, slimeParticles = 0, outside = 0;
            for (int i = 0; i < count; i++) {
                float w = pf.get(i * 4 + 3);
                if (w >= 4f) continue; // a filler between particles pulled apart
                boolean isSlime = w >= 2f;
                if (isSlime) slimeParticles++; else honeyParticles++;
                float[] bb = isSlime ? c : d;
                if (pf.get(i * 4) < bb[2] - 0.2f || pf.get(i * 4) > bb[5] + 0.2f || pf.get(i * 4 + 1) < bb[3] - 0.2f || pf.get(i * 4 + 1) > bb[6] + 0.2f) outside++;
            }
            check("particles for drawing: too small a buffer is refused, then all of them come", need < 0 && count > 0 && -need >= count * 4, "need " + need + ", count " + count);
            check("particles for drawing: all the honey and all the slime, where the goo is", honeyParticles == 2 * GooSim.BOTTLE && slimeParticles == 3 * GooSim.BALL && outside == 0,
                    honeyParticles + " honey, " + slimeParticles + " slime, " + outside + " outside");
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
            check("the honey stream lands in the same place", Math.abs(j[6] - r[6]) < 0.5f && Math.abs(j[7] - r[7]) < 0.6f, "middle " + j[6] + " vs " + r[6] + ", width " + j[7] + " vs " + r[7]);
            check("the slime heap lands in the same place", Math.abs(j[8] - r[8]) < 0.5f, j[8] + " vs " + r[8]);
        } else {
            System.out.println("Rust simulation skipped: " + NativeSim.loadMessage());
            if (args.length > 0 && args[0].equals("--require-native")) { System.out.println("  FAIL  native library required"); failures++; }
        }
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
