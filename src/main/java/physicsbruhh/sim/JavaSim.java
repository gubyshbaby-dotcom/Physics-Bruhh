package physicsbruhh.sim;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Pure-Java twin of the Rust core ({@code native/src/sim.rs} and {@code mesh.rs}); the two are
 * kept line-for-line comparable on purpose. See the Rust sources for the explanation of the model.
 */
public final class JavaSim implements GooSim {
    static final int MAX_SPR = 16;
    static final float DT = 1f / 60f;

    static final class Material {
        float meltTime, dampFresh, dampMelted, springK0, springK1, plast0, plast1, yield;
        boolean dynamicSprings;
        float breakStrain, springMaxL, shockK, shockPlast, shockDecay, shockBreak, shockMaxL, threadSag;
        float cohK, cohLim, xsph, adhK, adhCreep, adhBreak, setAfter, setRate, tip;

        static Material honey() {
            Material m = new Material();
            m.meltTime = 3.5f; m.dampFresh = 1.2f; m.dampMelted = 11f;
            m.springK0 = 0.5f; m.springK1 = 0.03f; m.plast0 = 0.7f; m.plast1 = 20f; m.yield = 0.03f;
            m.dynamicSprings = true; m.breakStrain = 4f; m.springMaxL = 0.21f; m.shockK = 0.42f; m.shockPlast = 0.6f; m.shockDecay = 1.1f; m.shockBreak = 16f; m.shockMaxL = 1.7f; m.threadSag = 0.16f;
            m.cohK = 0.25f; m.cohLim = 0.35f; m.xsph = 0.35f;
            m.adhK = 0.9f; m.adhCreep = 9f; m.adhBreak = 0.30f;
            m.setAfter = 11f; m.setRate = 0.3f; m.tip = 2.05f;
            return m;
        }

        static Material slime() {
            Material m = new Material();
            m.meltTime = 0f; m.dampFresh = 2.2f; m.dampMelted = 2.2f;
            m.springK0 = 0.45f; m.springK1 = 0.45f; m.plast0 = 0f; m.plast1 = 0f; m.yield = 0f;
            m.dynamicSprings = false; m.breakStrain = 8f; m.springMaxL = 9f; m.shockK = 0.45f; m.shockPlast = 0f; m.shockDecay = 0.3f; m.shockBreak = 12f; m.shockMaxL = 9f; m.threadSag = 0.03f;
            m.cohK = 0.15f; m.cohLim = 0.3f; m.xsph = 0.25f;
            m.adhK = 0.9f; m.adhCreep = 0f; m.adhBreak = 0.16f;
            m.setAfter = 0f; m.setRate = 0f; m.tip = 0f;
            return m;
        }
    }

    static final class Body {
        int state, mat, calm, count, anchored;
        boolean awake, quiet, fresh;
        float age, born, fade, melt, kd, kSpring, plast, brk, maxL, shock;
        final float[] bb = new float[6];

        void clear() { state = 0; mat = 0; calm = 0; count = 0; anchored = 0; awake = false; quiet = false; fresh = false; age = born = fade = melt = kd = kSpring = plast = brk = maxL = shock = 0f; Arrays.fill(bb, 0f); }
    }

    static final class KBox {
        int id; final float[] min = new float[3], max = new float[3], pmin = new float[3], pmax = new float[3], vel = new float[3]; float stick, t;
        boolean moving() { return vel[0] != 0f || vel[1] != 0f || vel[2] != 0f; }
    }

    final int n, nb, slots, maxp;
    final float s, h, side, rc;
    final Material[] mats = { Material.honey(), Material.slime() };
    float gravity = 9.8f, eps = 60f, kCorr = 2.0e-4f, killY = -64f;
    int iters = 3, viscIters = 1;
    final float[] p, q, v, scale;
    final byte[] alive;
    final byte[] ancKind, ancAxis; final int[] ancId; final float[] ancPt, ancLoc, ancStick, ancCool, ancD;
    final byte[] sprN; final int[] sprJ; final float[] sprL;
    final float[] rho, lam, sgx, sgy, sgz, sg2, dpx, dpy, dpz, damp;
    final int[] act; int nAct;
    final int mask; final int[] cStart, cFill, sorted, pkey, pbkt, cell;
    int[] pi = new int[4096], pj = new int[4096]; float[] pg = new float[4096 * 3], pw = new float[4096]; int nPair;
    final float kp, ks, w0, rho0, wq;
    final Body[] bodies;
    float[] st = new float[0]; int stCount; final int[] stOrg = new int[3], stDim = new int[3]; int[] stStart = { 0 }, stItems = new int[0];
    KBox[] kin = new KBox[0];
    float clock, acc; int seed = 0x9E3779B9;
    private float[] meshOut = new float[1 << 16]; private int meshLen;

    private static final int[] HX = { 0, 1, -1, 0, 1, -1, 0, 1, -1, 0, 1, -1, 0, 1 };
    private static final int[] HY = { 0, 0, 1, 1, 1, -1, -1, -1, 0, 0, 0, 1, 1, 1 };
    private static final int[] HZ = { 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1 };

    public JavaSim(int particlesPerEdge, int maxBodies) {
        n = Math.max(6, Math.min(16, particlesPerEdge));
        side = 0.97f; s = side / n; nb = n * n * n; slots = Math.max(1, maxBodies); maxp = nb * slots;
        h = 2.05f * s; rc = s * 0.5f;
        float h2 = h * h;
        kp = (float) (315.0 / (64.0 * Math.PI * Math.pow(h, 9)));
        ks = (float) (45.0 / (Math.PI * Math.pow(h, 6)));
        w0 = kp * h2 * h2 * h2;
        float r0 = 0f; int m = (int) Math.ceil(h / s);
        for (int a = -m; a <= m; a++) for (int b = -m; b <= m; b++) for (int c = -m; c <= m; c++) {
            float r2 = (a * a + b * b + c * c) * s * s;
            if (r2 < h2) { float t = h2 - r2; r0 += kp * t * t * t; }
        }
        rho0 = r0 * 0.97f;
        { float r = 0.25f * h, t = h2 - r * r; wq = kp * t * t * t; }
        int table = 1; while (table < maxp * 2) table <<= 1;
        mask = table - 1;
        p = new float[maxp * 3]; q = new float[maxp * 3]; v = new float[maxp * 3]; scale = new float[maxp]; alive = new byte[maxp];
        ancKind = new byte[maxp]; ancAxis = new byte[maxp]; ancId = new int[maxp]; ancPt = new float[maxp * 3]; ancLoc = new float[maxp * 3]; ancStick = new float[maxp]; ancCool = new float[maxp]; ancD = new float[maxp * 3];
        sprN = new byte[maxp]; sprJ = new int[maxp * MAX_SPR]; sprL = new float[maxp * MAX_SPR];
        rho = new float[maxp]; lam = new float[maxp]; sgx = new float[maxp]; sgy = new float[maxp]; sgz = new float[maxp]; sg2 = new float[maxp];
        dpx = new float[maxp]; dpy = new float[maxp]; dpz = new float[maxp]; damp = new float[maxp];
        act = new int[maxp];
        cStart = new int[table + 1]; cFill = new int[table]; sorted = new int[maxp]; pkey = new int[maxp]; pbkt = new int[maxp]; cell = new int[maxp * 3];
        bodies = new Body[slots];
        for (int b = 0; b < slots; b++) bodies[b] = new Body();
    }

    private static float smooth(float t) { t = t < 0f ? 0f : t > 1f ? 1f : t; return t * t * (3f - 2f * t); }
    private int hash3(int x, int y, int z) { return ((x * 73856093) ^ (y * 19349663) ^ (z * 83492791)) & mask; }
    private static int key3(int x, int y, int z) { return (x & 1023) | ((y & 1023) << 10) | ((z & 1023) << 20); }
    private float rnd() { seed = seed * 1664525 + 1013904223; return ((seed >>> 8) & 0xffff) / 65536f; }
    private static int ifloor(float x) { return (int) Math.floor(x); }

    @Override public String backend() { return "java"; }
    @Override public void close() { }
    @Override public void setKillY(float y) { killY = y; }

    private void freeBody(int b) {
        bodies[b].clear();
        int lo = b * nb, hi = (b + 1) * nb;
        for (int i = lo; i < hi; i++) { alive[i] = 0; scale[i] = 0f; sprN[i] = 0; ancKind[i] = 0; }
        for (int i = 0; i < maxp; i++) {
            int k = 0;
            while (k < sprN[i]) {
                int j = sprJ[i * MAX_SPR + k];
                if (j >= lo && j < hi) { int last = i * MAX_SPR + sprN[i] - 1; sprJ[i * MAX_SPR + k] = sprJ[last]; sprL[i * MAX_SPR + k] = sprL[last]; sprN[i]--; }
                else k++;
            }
        }
    }

    @Override
    public int addBody(int material, float x, float y, float z, float dirX, float dirZ, float age) {
        int mat = material == SLIME ? SLIME : HONEY;
        int slot = -1;
        for (int b = 0; b < slots; b++) if (bodies[b].state == 0) { slot = b; break; }
        if (slot < 0) {
            int best = -1; float bt = Float.MAX_VALUE;
            for (int b = 0; b < slots; b++) if (!bodies[b].awake && bodies[b].born < bt) { bt = bodies[b].born; best = b; }
            if (best < 0) for (int b = 0; b < slots; b++) if (bodies[b].born < bt) { bt = bodies[b].born; best = b; }
            if (best < 0) return -1;
            freeBody(best); slot = best;
        }
        Material m = mats[mat];
        int base = slot * nb;
        boolean puddle = mat == HONEY && age > 6f;
        float cx = x + 0.5f, cz = z + 0.5f;
        float dl = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
        float dx = dl > 1e-4f ? dirX / dl : 1f, dz = dl > 1e-4f ? dirZ / dl : 0f;
        int k = 0;
        for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) for (int c = 0; c < n; c++) {
            int i = base + k; k++;
            float jx = rnd() - 0.5f, jy = rnd() - 0.5f, jz = rnd() - 0.5f;
            float px, py, pz, vx, vy, vz;
            if (puddle) {
                float layer = k % 2;
                float rad = 1.27f * (float) Math.sqrt(rnd()), ang = rnd() * (float) (Math.PI * 2.0);
                px = cx + rad * (float) Math.cos(ang); pz = cz + rad * (float) Math.sin(ang); py = y + rc + layer * s * 0.9f + jy * s * 0.1f;
                vx = 0f; vy = 0f; vz = 0f;
            } else {
                float ox = (a + 0.5f) * s - side * 0.5f, oy = (b + 0.5f) * s, oz = (c + 0.5f) * s - side * 0.5f;
                px = cx + ox + jx * s * 0.02f; py = y + oy + jy * s * 0.02f; pz = cz + oz + jz * s * 0.02f;
                float along = ox * dx + oz * dz;
                float w = age < 0.5f ? m.tip : 0f;
                vx = dx * w * oy; vz = dz * w * oy; vy = w * (side * 0.5f - along);
            }
            int o = i * 3;
            p[o] = px; p[o + 1] = py; p[o + 2] = pz; q[o] = px; q[o + 1] = py; q[o + 2] = pz;
            v[o] = vx; v[o + 1] = vy; v[o + 2] = vz;
            alive[i] = 1; scale[i] = 1f; sprN[i] = 0; ancKind[i] = 0; ancCool[i] = 0f;
        }
        Body bd = bodies[slot];
        bd.clear();
        bd.state = 1; bd.mat = mat; bd.awake = true; bd.age = Math.max(age, 0f); bd.born = clock; bd.fade = 1f; bd.fresh = !puddle; bd.count = nb;
        bd.bb[0] = x - 0.5f; bd.bb[1] = y - 0.2f; bd.bb[2] = z - 0.5f; bd.bb[3] = x + 1.5f; bd.bb[4] = y + 1.3f; bd.bb[5] = z + 1.5f;
        return slot;
    }

    @Override
    public void removeBody(int b, boolean fade) {
        if (b < 0 || b >= slots || bodies[b].state == 0) return;
        if (fade) { bodies[b].state = 2; bodies[b].awake = true; } else freeBody(b);
    }

    @Override public void wake(int b) { if (b >= 0 && b < slots && bodies[b].state != 0) { bodies[b].awake = true; bodies[b].calm = 0; } }

    int awakeCount() { int c = 0; for (Body b : bodies) if (b.state != 0 && b.awake) c++; return c; }

    @Override
    public void setStatic(ByteBuffer buf, int count) {
        FloatBuffer data = buf.asFloatBuffer();
        count = Math.max(0, Math.min(count, data.capacity() / STATIC_FLOATS));
        if (st.length < count * 7) st = new float[count * 7];
        data.get(0, st, 0, count * 7);
        stCount = count;
        if (count == 0) { stDim[0] = stDim[1] = stDim[2] = 0; stStart = new int[] { 0 }; stItems = new int[0]; }
        else {
            int[] lo = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE }, hi = { Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
            for (int k = 0; k < count; k++) for (int a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], ifloor(st[k * 7 + a]) - 1); hi[a] = Math.max(hi[a], (int) Math.ceil(st[k * 7 + 3 + a]) + 1); }
            for (int a = 0; a < 3; a++) { stOrg[a] = lo[a]; stDim[a] = Math.max(1, Math.min(96, hi[a] - lo[a])); }
            int cells = stDim[0] * stDim[1] * stDim[2];
            int[] cnt = new int[cells + 1];
            int[] r = new int[6];
            for (int pass = 0; pass < 2; pass++) {
                if (pass == 1) { int run = 0; for (int c = 0; c <= cells; c++) { int t = cnt[c]; cnt[c] = run; run += t; } stStart = cnt.clone(); stItems = new int[run]; }
                for (int bi = 0; bi < count; bi++) {
                    for (int a = 0; a < 3; a++) {
                        r[a * 2] = Math.max(0, Math.min(stDim[a] - 1, ifloor(st[bi * 7 + a] - 0.01f) - stOrg[a]));
                        r[a * 2 + 1] = Math.max(0, Math.min(stDim[a] - 1, ifloor(st[bi * 7 + 3 + a] + 0.01f) - stOrg[a]));
                    }
                    for (int z = r[4]; z <= r[5]; z++) for (int y = r[2]; y <= r[3]; y++) for (int x = r[0]; x <= r[1]; x++) {
                        int c = x + stDim[0] * (y + stDim[1] * z);
                        if (pass == 0) cnt[c]++; else { stItems[cnt[c]] = bi; cnt[c]++; }
                    }
                }
            }
        }
        // anchors of awake goo whose surface is gone let go (sleeping goo is outside the scanned region)
        for (int i = 0; i < maxp; i++) if (alive[i] != 0 && ancKind[i] == 1 && bodies[i / nb].awake && !nearStatic(ancPt[i * 3], ancPt[i * 3 + 1], ancPt[i * 3 + 2], rc + 0.06f)) ancKind[i] = 0;
    }

    private boolean nearStatic(float px, float py, float pz, float tol) {
        if (stCount == 0) return false;
        int c0 = ifloor(px) - stOrg[0], c1 = ifloor(py) - stOrg[1], c2 = ifloor(pz) - stOrg[2];
        for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int x = c0 + dx, y = c1 + dy, z = c2 + dz;
            if (x < 0 || y < 0 || z < 0 || x >= stDim[0] || y >= stDim[1] || z >= stDim[2]) continue;
            int ci = x + stDim[0] * (y + stDim[1] * z);
            for (int k = stStart[ci]; k < stStart[ci + 1]; k++) {
                int o = stItems[k] * 7;
                if (px > st[o] - tol && px < st[o + 3] + tol && py > st[o + 1] - tol && py < st[o + 4] + tol && pz > st[o + 2] - tol && pz < st[o + 5] + tol) return true;
            }
        }
        return false;
    }

    @Override
    public void setKinematic(ByteBuffer buf, int count) {
        FloatBuffer data = buf.asFloatBuffer();
        count = Math.max(0, Math.min(count, data.capacity() / KINEMATIC_FLOATS));
        KBox[] next = new KBox[count];
        for (int k = 0; k < count; k++) {
            KBox b = new KBox(); int o = k * 11;
            b.id = (int) data.get(o);
            for (int a = 0; a < 3; a++) { b.min[a] = data.get(o + 1 + a); b.max[a] = data.get(o + 4 + a); b.vel[a] = data.get(o + 7 + a); }
            b.stick = data.get(o + 10);
            KBox prev = null; for (KBox kb : kin) if (kb.id == b.id) { prev = kb; break; }
            for (int a = 0; a < 3; a++) { b.pmin[a] = prev != null ? prev.min[a] : b.min[a]; b.pmax[a] = prev != null ? prev.max[a] : b.max[a]; }
            next[k] = b;
        }
        for (int i = 0; i < maxp; i++) {
            if (alive[i] != 0 && ancKind[i] == 2) { boolean found = false; for (KBox b : next) if (b.id == ancId[i]) { found = true; break; } if (!found) ancKind[i] = 1; }
        }
        kin = next;
    }

    @Override
    public int step(float dt) {
        acc += Math.max(0f, Math.min(0.1f, dt));
        int k = 0;
        while (acc >= DT && k < 3) { stepOnce(); acc -= DT; k++; }
        if (acc > DT * 2f) acc = 0f;
        return awakeCount();
    }

    private final float[] tq = new float[3], tp = new float[3];

    /** Returns the face axis that pushed the particle out, or 3 if the box was not touched. */
    private int resolveBox(float[] bq, float[] bp, float n0, float n1, float n2, float x0, float x1, float x2, float pn0, float pn1, float pn2, float px0, float px1, float px2) {
        float best = Float.MAX_VALUE; int ba = 3; boolean hi = false;
        for (int a = 0; a < 3; a++) {
            int b = (a + 1) % 3, c = (a + 2) % 3;
            float minB = b == 0 ? n0 : b == 1 ? n1 : n2, maxB = b == 0 ? x0 : b == 1 ? x1 : x2, minC = c == 0 ? n0 : c == 1 ? n1 : n2, maxC = c == 0 ? x0 : c == 1 ? x1 : x2;
            if (!(bq[b] > minB - rc && bq[b] < maxB + rc && bq[c] > minC - rc && bq[c] < maxC + rc)) continue;
            float minA = a == 0 ? n0 : a == 1 ? n1 : n2, maxA = a == 0 ? x0 : a == 1 ? x1 : x2, pminA = a == 0 ? pn0 : a == 1 ? pn1 : pn2, pmaxA = a == 0 ? px0 : a == 1 ? px1 : px2;
            if (bp[a] >= pmaxA + rc - 1e-4f && bq[a] < maxA + rc) { float pen = maxA + rc - bq[a]; if (pen < best) { best = pen; ba = a; hi = true; } }
            if (bp[a] <= pminA - rc + 1e-4f && bq[a] > minA - rc) { float pen = bq[a] - (minA - rc); if (pen < best) { best = pen; ba = a; hi = false; } }
        }
        if (ba < 3) { float mn = ba == 0 ? n0 : ba == 1 ? n1 : n2, mx = ba == 0 ? x0 : ba == 1 ? x1 : x2; bq[ba] = hi ? mx + rc : mn - rc; return ba; }
        if (bq[0] <= n0 - rc || bq[0] >= x0 + rc || bq[1] <= n1 - rc || bq[1] >= x1 + rc || bq[2] <= n2 - rc || bq[2] >= x2 + rc) return 3;
        best = Float.MAX_VALUE; ba = 0; hi = false;
        for (int a = 0; a < 3; a++) {
            float mn = a == 0 ? n0 : a == 1 ? n1 : n2, mx = a == 0 ? x0 : a == 1 ? x1 : x2;
            float penLo = bq[a] - (mn - rc); if (penLo < best) { best = penLo; ba = a; hi = false; }
            float penHi = (mx + rc) - bq[a]; if (penHi < best) { best = penHi; ba = a; hi = true; }
        }
        float mn = ba == 0 ? n0 : ba == 1 ? n1 : n2, mx = ba == 0 ? x0 : ba == 1 ? x1 : x2;
        bq[ba] = hi ? mx + rc : mn - rc;
        return ba;
    }

    private void collide(int i) {
        int o = i * 3;
        tq[0] = q[o]; tq[1] = q[o + 1]; tq[2] = q[o + 2]; tp[0] = p[o]; tp[1] = p[o + 1]; tp[2] = p[o + 2];
        int hit = 0, hitId = 0, hitAxis = 3; float hitStick = 0f, hm0 = 0f, hm1 = 0f, hm2 = 0f;
        if (stCount > 0) {
            int ax0 = ifloor(tq[0] - rc) - stOrg[0], ax1 = ifloor(tq[0] + rc) - stOrg[0], ay0 = ifloor(tq[1] - rc) - stOrg[1], ay1 = ifloor(tq[1] + rc) - stOrg[1], az0 = ifloor(tq[2] - rc) - stOrg[2], az1 = ifloor(tq[2] + rc) - stOrg[2];
            boolean inside = !(ax1 < 0 || ax0 >= stDim[0] || ay1 < 0 || ay0 >= stDim[1] || az1 < 0 || az0 >= stDim[2]);
            if (inside) {
                ax0 = Math.max(ax0, 0); ax1 = Math.min(ax1, stDim[0] - 1); ay0 = Math.max(ay0, 0); ay1 = Math.min(ay1, stDim[1] - 1); az0 = Math.max(az0, 0); az1 = Math.min(az1, stDim[2] - 1);
                for (int z = az0; z <= az1; z++) for (int y = ay0; y <= ay1; y++) for (int x = ax0; x <= ax1; x++) {
                    int ci = x + stDim[0] * (y + stDim[1] * z);
                    for (int k = stStart[ci]; k < stStart[ci + 1]; k++) {
                        int b = stItems[k] * 7;
                        int ax = resolveBox(tq, tp, st[b], st[b + 1], st[b + 2], st[b + 3], st[b + 4], st[b + 5], st[b], st[b + 1], st[b + 2], st[b + 3], st[b + 4], st[b + 5]);
                        if (ax < 3) { hit = 1; hitStick = st[b + 6]; hitAxis = ax; }
                    }
                }
            }
        }
        for (KBox b : kin) {
            int ax = resolveBox(tq, tp, b.min[0], b.min[1], b.min[2], b.max[0], b.max[1], b.max[2], b.pmin[0], b.pmin[1], b.pmin[2], b.pmax[0], b.pmax[1], b.pmax[2]);
            if (ax < 3) { hit = 2; hitId = b.id; hitStick = b.stick; hm0 = b.min[0]; hm1 = b.min[1]; hm2 = b.min[2]; hitAxis = ax; }
        }
        q[o] = tq[0]; q[o + 1] = tq[1]; q[o + 2] = tq[2];
        if (hit != 0 && ancKind[i] == 0 && ancCool[i] <= 0f && hitStick > 0f && rho[i] < 1.25f * rho0) {
            ancKind[i] = (byte) hit; ancId[i] = hitId; ancStick[i] = hitStick; ancAxis[i] = (byte) hitAxis;
            ancPt[o] = tq[0]; ancPt[o + 1] = tq[1]; ancPt[o + 2] = tq[2];
            ancLoc[o] = tq[0] - hm0; ancLoc[o + 1] = tq[1] - hm1; ancLoc[o + 2] = tq[2] - hm2;
        }
    }

    private boolean springExists(int a, int b) {
        for (int k = 0; k < sprN[a]; k++) if (sprJ[a * MAX_SPR + k] == b) return true;
        for (int k = 0; k < sprN[b]; k++) if (sprJ[b * MAX_SPR + k] == a) return true;
        return false;
    }

    private void addPair(int i, int j) {
        if (nPair == pi.length) { int cap = nPair * 2; pi = Arrays.copyOf(pi, cap); pj = Arrays.copyOf(pj, cap); pg = Arrays.copyOf(pg, cap * 3); pw = Arrays.copyOf(pw, cap); }
        pi[nPair] = i; pj[nPair] = j; nPair++;
    }

    private void stepOnce() {
        final float dt = DT;
        clock += dt;
        for (KBox b : kin) {
            if (!b.moving()) continue;
            for (Body bd : bodies) {
                if (bd.state == 0) continue;
                boolean near6 = true, near3 = true;
                for (int a = 0; a < 3; a++) { if (!(b.max[a] > bd.bb[a] - 0.6f && b.min[a] < bd.bb[a + 3] + 0.6f)) near6 = false; if (!(b.max[a] > bd.bb[a] - 0.3f && b.min[a] < bd.bb[a + 3] + 0.3f)) near3 = false; }
                if (!bd.awake && near6) { bd.awake = true; bd.calm = 0; }
                if (near3) bd.shock = 1f;
            }
        }
        // ---- bodies ----
        nAct = 0;
        for (int b = 0; b < slots; b++) {
            Body bd = bodies[b];
            if (bd.state == 0 || !bd.awake) continue;
            Material m = mats[bd.mat];
            bd.age += dt;
            bd.melt = m.meltTime > 0f ? smooth(bd.age / m.meltTime) : 0f;
            float set = m.setAfter > 0f ? 1f + Math.min(3f, Math.max(0f, bd.age - m.setAfter) * m.setRate) : 1f;
            float sh = bd.shock;
            bd.kd = (m.dampFresh + (m.dampMelted - m.dampFresh) * bd.melt) * set + 26f * sh * sh;
            float k = m.springK0 + (m.springK1 - m.springK0) * bd.melt;
            bd.kSpring = k + Math.max(0f, m.shockK - k) * sh;
            float pl = m.plast0 + (m.plast1 - m.plast0) * bd.melt;
            bd.plast = pl + Math.min(0f, m.shockPlast - pl) * sh;
            bd.brk = m.breakStrain + Math.max(0f, m.shockBreak - m.breakStrain) * sh;
            bd.maxL = m.springMaxL + Math.max(0f, m.shockMaxL - m.springMaxL) * sh;
            bd.shock = sh < 0.01f ? 0f : sh * (float) Math.exp(-dt / m.shockDecay);
            boolean fadeDone = false;
            if (bd.state == 2) { bd.fade -= dt / 0.6f; if (bd.fade <= 0f) fadeDone = true; }
            if (fadeDone) { freeBody(b); continue; }
            boolean grounded = bd.anchored > 0 || bd.shock > 0.05f, fading = bd.state == 2;
            int cnt = 0;
            for (int i = b * nb; i < (b + 1) * nb; i++) {
                if (alive[i] != 0) { act[nAct++] = i; cnt++; damp[i] = grounded ? bd.kd : 0.15f; if (fading) scale[i] = bd.fade; }
            }
            bd.count = cnt;
            if (cnt == 0) freeBody(b);
        }
        if (nAct == 0) return;
        // ---- kinematic colliders move ----
        for (KBox b : kin) {
            // right after an update the sweep starts where the box really was before it
            if (b.t > 0f) { for (int a = 0; a < 3; a++) { b.pmin[a] = b.min[a]; b.pmax[a] = b.max[a]; } }
            b.t += dt;
            // the game reports 20 times a second: carry a box for one game tick, then wait for news
            if (b.t > 0.05f + 0.5f * dt) { b.vel[0] = 0f; b.vel[1] = 0f; b.vel[2] = 0f; }
            for (int a = 0; a < 3; a++) { b.min[a] += b.vel[a] * dt; b.max[a] += b.vel[a] * dt; }
        }
        // ---- predict ----
        final float g = gravity;
        for (int a = 0; a < nAct; a++) {
            int i = act[a], o = i * 3;
            float x = p[o], z = p[o + 2];
            float k = damp[i];
            if (k > 0.5f) k *= 0.72f + 0.34f * ((float) Math.sin(x * 6.1f + 1.9f * (float) Math.sin(z * 2.3f)) + (float) Math.sin(z * 3.7f + x * 1.4f));
            float f = (float) Math.exp(-k * dt);
            v[o] *= f; v[o + 1] = (v[o + 1] - g * dt) * f; v[o + 2] *= f;
            q[o] = p[o] + v[o] * dt; q[o + 1] = p[o + 1] + v[o + 1] * dt; q[o + 2] = p[o + 2] + v[o + 2] * dt;
            if (ancCool[i] > 0f) ancCool[i] -= dt;
        }
        for (int a = 0; a < nAct; a++) collide(act[a]);
        // ---- neighbour pairs ----
        final float h2 = h * h, ih = 1f / h;
        Arrays.fill(cStart, 0);
        for (int a = 0; a < nAct; a++) {
            int i = act[a], o = i * 3;
            int ix = ifloor(q[o] * ih) + 512, iy = ifloor(q[o + 1] * ih) + 512, iz = ifloor(q[o + 2] * ih) + 512;
            cell[o] = ix; cell[o + 1] = iy; cell[o + 2] = iz;
            pkey[i] = key3(ix, iy, iz);
            int bk = hash3(ix, iy, iz); pbkt[i] = bk; cStart[bk + 1]++;
        }
        for (int c = 0; c <= mask; c++) { cStart[c + 1] += cStart[c]; cFill[c] = cStart[c]; }
        for (int a = 0; a < nAct; a++) { int i = act[a]; sorted[cFill[pbkt[i]]++] = i; }
        nPair = 0;
        for (int a = 0; a < nAct; a++) {
            int i = act[a], o = i * 3;
            float x = q[o], y = q[o + 1], z = q[o + 2];
            int ix = cell[o], iy = cell[o + 1], iz = cell[o + 2];
            for (int c = 0; c < 14; c++) {
                int jx = ix + HX[c], jy = iy + HY[c], jz = iz + HZ[c];
                int key = key3(jx, jy, jz), bk = hash3(jx, jy, jz);
                for (int k = cStart[bk]; k < cStart[bk + 1]; k++) {
                    int j = sorted[k];
                    if (pkey[j] != key || (c == 0 && j <= i)) continue;
                    int oj = j * 3; float ex = x - q[oj], ey = y - q[oj + 1], ez = z - q[oj + 2];
                    if (ex * ex + ey * ey + ez * ez < h2) addPair(i, j);
                }
            }
        }
        // ---- springs form ----
        final float hs2 = (1.5f * s) * (1.5f * s);
        for (int k = 0; k < nPair; k++) {
            int i = pi[k], j = pj[k], bi = i / nb, bj = j / nb;
            if (bodies[bi].mat != bodies[bj].mat) continue;
            Material m = mats[bodies[bi].mat];
            if (!(m.dynamicSprings || (bi == bj && bodies[bi].fresh))) continue;
            int oi = i * 3, oj = j * 3;
            float ex = q[oi] - q[oj], ey = q[oi + 1] - q[oj + 1], ez = q[oi + 2] - q[oj + 2];
            float r2 = ex * ex + ey * ey + ez * ez;
            if (r2 > hs2) continue;
            int a = sprN[i] <= sprN[j] ? i : j, b = a == i ? j : i;
            int na = sprN[a];
            if (na >= MAX_SPR || springExists(i, j)) continue;
            sprJ[a * MAX_SPR + na] = b; sprL[a * MAX_SPR + na] = Math.max((float) Math.sqrt(r2), 0.7f * s); sprN[a]++;
        }
        for (Body bd : bodies) bd.fresh = false;
        // ---- constraint iterations ----
        final float ir0 = 1f / rho0, iwq = 1f / wq, maxD = 0.9f * s;
        for (int it = 0; it < iters; it++) {
            boolean last = it + 1 == iters;
            for (int a = 0; a < nAct; a++) { int i = act[a]; rho[i] = w0; sgx[i] = 0f; sgy[i] = 0f; sgz[i] = 0f; sg2[i] = 0f; dpx[i] = 0f; dpy[i] = 0f; dpz[i] = 0f; }
            for (int k = 0; k < nPair; k++) {
                int i = pi[k], j = pj[k], oi = i * 3, oj = j * 3;
                float ex = q[oi] - q[oj], ey = q[oi + 1] - q[oj + 1], ez = q[oi + 2] - q[oj + 2];
                float r2 = ex * ex + ey * ey + ez * ez;
                if (r2 >= h2) { pw[k] = 0f; continue; }
                float r = (float) Math.sqrt(r2) + 1e-7f, t = h2 - r2, w = kp * t * t * t, u = h - r, gm = -ks * u * u / r;
                float gx = gm * ex, gy = gm * ey, gz = gm * ez, g2 = gx * gx + gy * gy + gz * gz;
                pw[k] = w; pg[k * 3] = gx; pg[k * 3 + 1] = gy; pg[k * 3 + 2] = gz;
                rho[i] += w; rho[j] += w;
                sgx[i] += gx; sgy[i] += gy; sgz[i] += gz; sgx[j] -= gx; sgy[j] -= gy; sgz[j] -= gz;
                sg2[i] += g2; sg2[j] += g2;
            }
            for (int a = 0; a < nAct; a++) {
                int i = act[a];
                Material m = mats[bodies[i / nb].mat];
                float c = rho[i] * ir0 - 1f;
                if (c < 0f) c = Math.max(c, -m.cohLim) * m.cohK;
                float den = (sg2[i] + sgx[i] * sgx[i] + sgy[i] * sgy[i] + sgz[i] * sgz[i]) * ir0 * ir0 + eps;
                lam[i] = -c / den;
            }
            for (int k = 0; k < nPair; k++) {
                float w = pw[k]; if (w == 0f) continue;
                int i = pi[k], j = pj[k];
                float qq = w * iwq; qq *= qq; qq *= qq;
                float f = (lam[i] + lam[j] - kCorr * qq) * ir0;
                float fx = f * pg[k * 3], fy = f * pg[k * 3 + 1], fz = f * pg[k * 3 + 2];
                dpx[i] += fx; dpy[i] += fy; dpz[i] += fz; dpx[j] -= fx; dpy[j] -= fy; dpz[j] -= fz;
            }
            for (int a = 0; a < nAct; a++) {
                int i = act[a], o = i * 3;
                float x = dpx[i], y = dpy[i], z = dpz[i], l2 = x * x + y * y + z * z;
                if (l2 > maxD * maxD) { float sc = maxD / (float) Math.sqrt(l2); x *= sc; y *= sc; z *= sc; }
                q[o] += x; q[o + 1] += y; q[o + 2] += z;
            }
            for (int a = 0; a < nAct; a++) {
                int i = act[a], oi = i * 3;
                float ksp = bodies[i / nb].kSpring;
                for (int sidx = 0; sidx < sprN[i]; sidx++) {
                    int j = sprJ[i * MAX_SPR + sidx], oj = j * 3;
                    float l = sprL[i * MAX_SPR + sidx];
                    boolean fixed = !bodies[j / nb].awake;
                    float jx = fixed ? p[oj] : q[oj], jy = fixed ? p[oj + 1] : q[oj + 1], jz = fixed ? p[oj + 2] : q[oj + 2];
                    float ex = q[oi] - jx, ey = q[oi + 1] - jy, ez = q[oi + 2] - jz;
                    float r = (float) Math.sqrt(ex * ex + ey * ey + ez * ez) + 1e-7f;
                    float c = ksp * (r - l) / r * (fixed ? 1f : 0.5f);
                    q[oi] -= ex * c; q[oi + 1] -= ey * c; q[oi + 2] -= ez * c;
                    if (!fixed) { q[oj] += ex * c; q[oj + 1] += ey * c; q[oj + 2] += ez * c; }
                }
            }
            for (int a = 0; a < nAct; a++) {
                int i = act[a], o = i * 3;
                int kind = ancKind[i];
                if (kind != 0) {
                    if (kind == 2) { for (KBox b : kin) if (b.id == ancId[i]) { ancPt[o] = b.min[0] + ancLoc[o]; ancPt[o + 1] = b.min[1] + ancLoc[o + 1]; ancPt[o + 2] = b.min[2] + ancLoc[o + 2]; break; } }
                    float ak = mats[bodies[i / nb].mat].adhK;
                    for (int c = 0; c < 3; c++) { float d = q[o + c] - ancPt[o + c]; if (last) ancD[o + c] = d; q[o + c] -= ak * d; }
                }
                collide(i);
            }
        }
        // ---- anchors creep / snap, springs flow / snap ----
        for (int a = 0; a < nAct; a++) {
            int i = act[a], o = i * 3;
            Body bd = bodies[i / nb];
            Material m = mats[bd.mat];
            if (ancKind[i] != 0) {
                float ex = ancD[o], ey = ancD[o + 1], ez = ancD[o + 2];
                float d = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (d > m.adhBreak * ancStick[i] || rho[i] > 1.6f * rho0) { ancKind[i] = 0; ancCool[i] = 0.35f; }
                else if (m.adhCreep > 0f) {
                    float cf = 1f - (float) Math.exp(-m.adhCreep * (0.25f + 0.75f * bd.melt) * dt);
                    int ax = ancAxis[i];
                    if (ax != 0) { ancPt[o] += ex * cf; ancLoc[o] += ex * cf; }
                    if (ax != 1) { ancPt[o + 1] += ey * cf; ancLoc[o + 1] += ey * cf; }
                    if (ax != 2) { ancPt[o + 2] += ez * cf; ancLoc[o + 2] += ez * cf; }
                }
            }
            int sidx = 0;
            while (sidx < sprN[i]) {
                int si = i * MAX_SPR + sidx, j = sprJ[si], oj = j * 3;
                float l = sprL[si];
                float ex = q[o] - q[oj], ey = q[o + 1] - q[oj + 1], ez = q[o + 2] - q[oj + 2];
                float r = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (bd.plast > 0f) {
                    float dl = r - l, y = m.yield * l;
                    if (Math.abs(dl) > y) { l += (1f - (float) Math.exp(-bd.plast * dt)) * (dl - y * Math.signum(dl)); sprL[si] = l; }
                }
                boolean gone = alive[j] == 0 || r > bd.brk * l || l > bd.maxL;
                if (gone) { int lastS = i * MAX_SPR + sprN[i] - 1; sprJ[si] = sprJ[lastS]; sprL[si] = sprL[lastS]; sprN[i]--; }
                else sidx++;
            }
        }
        // ---- velocities, viscosity, commit ----
        final float idt = 1f / dt, maxMove = 0.3f;
        for (int a = 0; a < nAct; a++) {
            int i = act[a], o = i * 3;
            float ex = q[o] - p[o], ey = q[o + 1] - p[o + 1], ez = q[o + 2] - p[o + 2], d2 = ex * ex + ey * ey + ez * ez;
            if (d2 > maxMove * maxMove) { float sc = maxMove / (float) Math.sqrt(d2); q[o] = p[o] + ex * sc; q[o + 1] = p[o + 1] + ey * sc; q[o + 2] = p[o + 2] + ez * sc; }
            v[o] = (q[o] - p[o]) * idt; v[o + 1] = (q[o + 1] - p[o + 1]) * idt; v[o + 2] = (q[o + 2] - p[o + 2]) * idt;
        }
        for (int vi = 0; vi < viscIters; vi++) {
            for (int a = 0; a < nAct; a++) { int i = act[a]; dpx[i] = 0f; dpy[i] = 0f; dpz[i] = 0f; }
            for (int k = 0; k < nPair; k++) {
                float w = pw[k]; if (w == 0f) continue;
                int i = pi[k], j = pj[k], oi = i * 3, oj = j * 3;
                float ex = v[oj] - v[oi], ey = v[oj + 1] - v[oi + 1], ez = v[oj + 2] - v[oi + 2];
                float wi = mats[bodies[i / nb].mat].xsph * w / rho[i], wj = mats[bodies[j / nb].mat].xsph * w / rho[j];
                dpx[i] += ex * wi; dpy[i] += ey * wi; dpz[i] += ez * wi; dpx[j] -= ex * wj; dpy[j] -= ey * wj; dpz[j] -= ez * wj;
            }
            for (int a = 0; a < nAct; a++) { int i = act[a], o = i * 3; v[o] += dpx[i]; v[o + 1] += dpy[i]; v[o + 2] += dpz[i]; }
        }
        float[] vsum = new float[slots];
        for (Body bd : bodies) if (bd.awake) { bd.bb[0] = bd.bb[1] = bd.bb[2] = Float.MAX_VALUE; bd.bb[3] = bd.bb[4] = bd.bb[5] = -Float.MAX_VALUE; bd.anchored = 0; }
        for (int a = 0; a < nAct; a++) {
            int i = act[a], o = i * 3, b = i / nb;
            float sp = 0f;
            for (int c = 0; c < 3; c++) { p[o + c] = q[o + c]; sp += v[o + c] * v[o + c]; }
            if (p[o + 1] < killY || Float.isNaN(sp) || Float.isInfinite(sp)) { alive[i] = 0; scale[i] = 0f; sprN[i] = 0; ancKind[i] = 0; continue; }
            vsum[b] += sp;
            Body bd = bodies[b];
            for (int c = 0; c < 3; c++) { bd.bb[c] = Math.min(bd.bb[c], p[o + c]); bd.bb[c + 3] = Math.max(bd.bb[c + 3], p[o + c]); }
            if (ancKind[i] != 0) bd.anchored++;
        }
        // ---- sleep / wake ----
        for (int b = 0; b < slots; b++) {
            Body bd = bodies[b];
            if (bd.state == 0 || !bd.awake) continue;
            float vrms = (float) Math.sqrt(vsum[b] / Math.max(1, bd.count));
            bd.quiet = bd.state == 1 && vrms < 0.012f && bd.age > mats[bd.mat].meltTime + 1f;
            bd.calm = bd.quiet ? bd.calm + 1 : 0;
        }
        for (int b = 0; b < slots; b++) {
            Body bd = bodies[b];
            if (bd.state == 0 || !bd.awake) continue;
            boolean maySleep = bd.calm > 120;
            for (int c = 0; c < slots; c++) {
                Body cb = bodies[c];
                if (c == b || cb.state == 0) continue;
                boolean over = true;
                for (int a = 0; a < 3; a++) if (!(bd.bb[a] < cb.bb[a + 3] + 0.3f && bd.bb[a + 3] > cb.bb[a] - 0.3f)) { over = false; break; }
                if (!over) continue;
                if (!bd.quiet && !cb.awake) { cb.awake = true; cb.calm = 0; }
                if (cb.awake && !cb.quiet) maySleep = false;
            }
            for (KBox k : kin) {
                if (!k.moving()) continue;
                boolean near = true;
                for (int a = 0; a < 3; a++) if (!(k.max[a] > bd.bb[a] - 0.6f && k.min[a] < bd.bb[a + 3] + 0.6f)) { near = false; break; }
                if (near) { maySleep = false; bd.calm = 0; }
            }
            if (maySleep) { bd.awake = false; Arrays.fill(v, b * nb * 3, (b + 1) * nb * 3, 0f); }
        }
    }

    // ------------------------------------------------------------------------------------------
    // surface extraction (mesh.rs)
    // ------------------------------------------------------------------------------------------

    private void emit(float a, float b, float c, float d, float e, float f) {
        if (meshLen + 6 > meshOut.length) meshOut = Arrays.copyOf(meshOut, meshOut.length * 2);
        meshOut[meshLen++] = a; meshOut[meshLen++] = b; meshOut[meshLen++] = c; meshOut[meshLen++] = d; meshOut[meshLen++] = e; meshOut[meshLen++] = f;
    }

    @Override
    public int mesh(int material, ByteBuffer out) {
        meshLen = 0;
        int[] ids = new int[slots]; int nIds = 0;
        for (int b = 0; b < slots; b++) if (bodies[b].state != 0 && bodies[b].mat == material && bodies[b].count > 0) ids[nIds++] = b;
        int[] group = new int[nIds];
        for (int a = 0; a < nIds; a++) group[a] = a;
        float pad = 3f * s;
        for (int a = 0; a < nIds; a++) for (int b = a + 1; b < nIds; b++) {
            float[] x = bodies[ids[a]].bb, y = bodies[ids[b]].bb; boolean over = true;
            for (int k = 0; k < 3; k++) if (!(x[k] < y[k + 3] + pad && x[k + 3] > y[k] - pad)) { over = false; break; }
            if (over) { int ra = find(group, a), rb = find(group, b); group[ra] = rb; }
        }
        boolean[] done = new boolean[nIds];
        for (int a = 0; a < nIds; a++) {
            int root = find(group, a);
            if (done[root]) continue;
            done[root] = true;
            int[] members = new int[nIds]; int nm = 0;
            for (int b = 0; b < nIds; b++) if (find(group, b) == root) members[nm++] = ids[b];
            meshCluster(members, nm);
        }
        threads(material);
        FloatBuffer fb = out.asFloatBuffer();
        if (meshLen > fb.capacity()) return -meshLen;
        fb.put(0, meshOut, 0, meshLen);
        return meshLen / FLOATS_PER_QUAD;
    }

    private static int find(int[] g, int a) { while (g[a] != a) { g[a] = g[g[a]]; a = g[a]; } return a; }

    private static final int[][] CO = { { 0, 0, 0 }, { 1, 0, 0 }, { 0, 1, 0 }, { 1, 1, 0 }, { 0, 0, 1 }, { 1, 0, 1 }, { 0, 1, 1 }, { 1, 1, 1 } };
    private static final int[][] ED = { { 0, 1 }, { 2, 3 }, { 4, 5 }, { 6, 7 }, { 0, 2 }, { 1, 3 }, { 4, 6 }, { 5, 7 }, { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };

    private void meshCluster(int[] members, int nm) {
        float rk = 2.2f * s, rad = 0.92f * s;
        float[] lo = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE }, hi = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (int mi = 0; mi < nm; mi++) { float[] bb = bodies[members[mi]].bb; for (int k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], bb[k]); hi[k] = Math.max(hi[k], bb[k + 3]); } }
        if (lo[0] > hi[0]) return;
        float c = s; int[] dim = new int[3];
        while (true) {
            long cells = 1;
            for (int k = 0; k < 3; k++) { dim[k] = (int) Math.ceil((hi[k] - lo[k] + 2f * (rk + c)) / c) + 1; cells *= dim[k]; }
            if (cells <= 3_000_000L) break;
            c *= 1.26f;
        }
        float o0 = lo[0] - rk - c, o1 = lo[1] - rk - c, o2 = lo[2] - rk - c;
        int nx = dim[0], ny = dim[1], nz = dim[2], nn = nx * ny * nz;
        float[] accm = new float[nn * 4];
        float ic = 1f / c, irk2 = 1f / (rk * rk);
        for (int mi = 0; mi < nm; mi++) {
            int b = members[mi];
            for (int i = b * nb; i < (b + 1) * nb; i++) {
                if (alive[i] == 0) continue;
                float sc = scale[i]; if (sc <= 0.01f) continue;
                float px = p[i * 3], py = p[i * 3 + 1], pz = p[i * 3 + 2];
                int x0 = Math.max(0, (int) Math.ceil((px - rk - o0) * ic)), x1 = Math.min(nx - 1, (int) Math.floor((px + rk - o0) * ic));
                int y0 = Math.max(0, (int) Math.ceil((py - rk - o1) * ic)), y1 = Math.min(ny - 1, (int) Math.floor((py + rk - o1) * ic));
                int z0 = Math.max(0, (int) Math.ceil((pz - rk - o2) * ic)), z1 = Math.min(nz - 1, (int) Math.floor((pz + rk - o2) * ic));
                for (int z = z0; z <= z1; z++) { float dz = o2 + z * c - pz;
                    for (int y = y0; y <= y1; y++) { float dy = o1 + y * c - py;
                        for (int x = x0; x <= x1; x++) { float dx = o0 + x * c - px;
                            float t = 1f - (dx * dx + dy * dy + dz * dz) * irk2;
                            if (t <= 0f) continue;
                            float wgt = t * t * t * sc; int o = (x + nx * (y + ny * z)) * 4;
                            accm[o] += wgt; accm[o + 1] += wgt * px; accm[o + 2] += wgt * py; accm[o + 3] += wgt * pz;
                        }
                    }
                }
            }
        }
        float[] phi = new float[nn];
        for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
            int nd = x + nx * (y + ny * z); float wsum = accm[nd * 4];
            if (wsum > 1e-5f) {
                float inv = 1f / wsum;
                float dx = o0 + x * c - accm[nd * 4 + 1] * inv, dy = o1 + y * c - accm[nd * 4 + 2] * inv, dz = o2 + z * c - accm[nd * 4 + 3] * inv;
                float r = rad * (0.55f + 0.45f * Math.min(1f, wsum * 0.9f));
                phi[nd] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
            } else phi[nd] = rk;
        }
        int cx = nx - 1, cy = ny - 1, cz = nz - 1;
        int[] vid = new int[cx * cy * cz];
        Arrays.fill(vid, -1);
        float[] verts = new float[4096]; int nv = 0;
        float[] f = new float[8];
        for (int z = 0; z < cz; z++) for (int y = 0; y < cy; y++) for (int x = 0; x < cx; x++) {
            int m = 0;
            for (int k = 0; k < 8; k++) { f[k] = phi[(x + CO[k][0]) + nx * ((y + CO[k][1]) + ny * (z + CO[k][2]))]; if (f[k] < 0f) m |= 1 << k; }
            if (m == 0 || m == 255) continue;
            float px = 0f, py = 0f, pz = 0f, cnt = 0f;
            for (int[] e : ED) {
                int a = e[0], b = e[1];
                if ((f[a] < 0f) == (f[b] < 0f)) continue;
                float t = f[a] / (f[a] - f[b]);
                px += CO[a][0] + t * (CO[b][0] - CO[a][0]); py += CO[a][1] + t * (CO[b][1] - CO[a][1]); pz += CO[a][2] + t * (CO[b][2] - CO[a][2]);
                cnt += 1f;
            }
            float inv = 1f / cnt;
            float gx = (f[1] + f[3] + f[5] + f[7]) - (f[0] + f[2] + f[4] + f[6]);
            float gy = (f[2] + f[3] + f[6] + f[7]) - (f[0] + f[1] + f[4] + f[5]);
            float gz = (f[4] + f[5] + f[6] + f[7]) - (f[0] + f[1] + f[2] + f[3]);
            float gl = Math.max(1e-6f, (float) Math.sqrt(gx * gx + gy * gy + gz * gz));
            vid[x + cx * (y + cy * z)] = nv / 6;
            if (nv + 6 > verts.length) verts = Arrays.copyOf(verts, verts.length * 2);
            float ux = gx / gl, uy = gy / gl, uz = gz / gl;
            float vx = x + px * inv, vy = y + py * inv, vz = z + pz * inv;
            // how much goo lies behind this point, measured straight inwards: the renderer tints by it
            float depth = 0f;
            for (int st = 1; st <= 16; st++) {
                float sx = (float) Math.floor(vx - ux * st + 0.5f), sy = (float) Math.floor(vy - uy * st + 0.5f), sz = (float) Math.floor(vz - uz * st + 0.5f);
                if (sx < 0f || sy < 0f || sz < 0f || sx >= nx || sy >= ny || sz >= nz) break;
                if (phi[(int) sx + nx * ((int) sy + ny * (int) sz)] >= 0f) break;
                depth = st * c;
            }
            float nl = 1f + depth;
            verts[nv++] = o0 + vx * c; verts[nv++] = o1 + vy * c; verts[nv++] = o2 + vz * c;
            verts[nv++] = ux * nl; verts[nv++] = uy * nl; verts[nv++] = uz * nl;
        }
        for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
            boolean in0 = phi[x + nx * (y + ny * z)] < 0f;
            if (x + 1 < nx && y >= 1 && z >= 1 && y < ny - 1 && z < nz - 1 && in0 != (phi[x + 1 + nx * (y + ny * z)] < 0f))
                quad(verts, vid[x + cx * (y - 1 + cy * (z - 1))], vid[x + cx * (y + cy * (z - 1))], vid[x + cx * (y + cy * z)], vid[x + cx * (y - 1 + cy * z)], !in0);
            if (y + 1 < ny && x >= 1 && z >= 1 && x < nx - 1 && z < nz - 1 && in0 != (phi[x + nx * (y + 1 + ny * z)] < 0f))
                quad(verts, vid[x - 1 + cx * (y + cy * (z - 1))], vid[x - 1 + cx * (y + cy * z)], vid[x + cx * (y + cy * z)], vid[x + cx * (y + cy * (z - 1))], !in0);
            if (z + 1 < nz && x >= 1 && y >= 1 && x < nx - 1 && y < ny - 1 && in0 != (phi[x + nx * (y + ny * (z + 1))] < 0f))
                quad(verts, vid[x - 1 + cx * (y - 1 + cy * z)], vid[x + cx * (y - 1 + cy * z)], vid[x + cx * (y + cy * z)], vid[x - 1 + cx * (y + cy * z)], !in0);
        }
    }

    private void quad(float[] verts, int a, int b, int c, int d, boolean flip) {
        if (a < 0 || b < 0 || c < 0 || d < 0) return;
        int v0 = flip ? d : a, v1 = flip ? c : b, v2 = flip ? b : c, v3 = flip ? a : d;
        for (int vtx : new int[] { v0, v1, v2, v3 }) { int o = vtx * 6; emit(verts[o], verts[o + 1], verts[o + 2], verts[o + 3], verts[o + 4], verts[o + 5]); }
    }

    private void threads(int mat) {
        float minLen = 1.9f * s, sag = mats[mat].threadSag;
        int budget = 6000;
        float[] t = new float[3], u = new float[3], w = new float[3], p0 = new float[3], p1 = new float[3];
        for (int b = 0; b < slots; b++) {
            if (bodies[b].state == 0 || bodies[b].mat != mat) continue;
            for (int i = b * nb; i < (b + 1) * nb; i++) {
                if (alive[i] == 0) continue;
                for (int k = 0; k < sprN[i]; k++) {
                    int j = sprJ[i * MAX_SPR + k];
                    if (alive[j] == 0) continue;
                    float ax = p[i * 3], ay = p[i * 3 + 1], az = p[i * 3 + 2], bx = p[j * 3], by = p[j * 3 + 1], bz = p[j * 3 + 2];
                    float dx = bx - ax, dy = by - ay, dz = bz - az;
                    float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (len < minLen || len > 4f || (len < 2.9f * s && len < 1.3f * sprL[i * MAX_SPR + k])) continue;
                    if (Integer.remainderUnsigned((i * 31) ^ (j * 17), 3) != 0) continue;
                    if (budget < 4) return;
                    budget -= 4;
                    float half = Math.max(0.006f, Math.min(0.03f, 0.42f * s * (float) Math.sqrt(minLen / len))) * Math.min(scale[i], scale[j]);
                    float mx = ax + dx * 0.5f, my = ay + dy * 0.5f - sag * len, mz = az + dz * 0.5f;
                    for (int seg = 0; seg < 2; seg++) {
                        if (seg == 0) { p0[0] = ax; p0[1] = ay; p0[2] = az; p1[0] = mx; p1[1] = my; p1[2] = mz; } else { p0[0] = mx; p0[1] = my; p0[2] = mz; p1[0] = bx; p1[1] = by; p1[2] = bz; }
                        t[0] = p1[0] - p0[0]; t[1] = p1[1] - p0[1]; t[2] = p1[2] - p0[2];
                        float tl = (float) Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
                        float upx = Math.abs(t[1]) > 0.9f * tl ? 1f : 0f, upy = Math.abs(t[1]) > 0.9f * tl ? 0f : 1f;
                        u[0] = t[1] * 0f - t[2] * upy; u[1] = t[2] * upx - t[0] * 0f; u[2] = t[0] * upy - t[1] * upx;
                        float ul = Math.max(1e-6f, (float) Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2])); u[0] /= ul; u[1] /= ul; u[2] /= ul;
                        w[0] = t[1] * u[2] - t[2] * u[1]; w[1] = t[2] * u[0] - t[0] * u[2]; w[2] = t[0] * u[1] - t[1] * u[0];
                        float wl = Math.max(1e-6f, (float) Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])); w[0] /= wl; w[1] /= wl; w[2] /= wl;
                        for (int r = 0; r < 2; r++) {
                            float[] sd = r == 0 ? u : w, nr = r == 0 ? w : u;
                            emit(p0[0] - sd[0] * half, p0[1] - sd[1] * half, p0[2] - sd[2] * half, nr[0], nr[1], nr[2]);
                            emit(p0[0] + sd[0] * half, p0[1] + sd[1] * half, p0[2] + sd[2] * half, nr[0], nr[1], nr[2]);
                            emit(p1[0] + sd[0] * half, p1[1] + sd[1] * half, p1[2] + sd[2] * half, nr[0], nr[1], nr[2]);
                            emit(p1[0] - sd[0] * half, p1[1] - sd[1] * half, p1[2] - sd[2] * half, nr[0], nr[1], nr[2]);
                        }
                    }
                }
            }
        }
    }

    @Override
    public int bodies(ByteBuffer out) {
        FloatBuffer fb = out.asFloatBuffer();
        int cnt = Math.min(slots, fb.capacity() / BODY_FLOATS);
        for (int b = 0; b < cnt; b++) {
            Body bd = bodies[b];
            fb.put(b * 8, bd.state); fb.put(b * 8 + 1, bd.awake ? 1f : 0f);
            for (int k = 0; k < 6; k++) fb.put(b * 8 + 2 + k, bd.bb[k]);
        }
        return cnt;
    }
}
