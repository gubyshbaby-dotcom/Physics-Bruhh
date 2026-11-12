package physicsbruhh.sim;

import java.util.Arrays;

/**
 * The surface of the lava in one 16×16×16 section of the world, worked out from what the blocks
 * say (which are lava and how full, which way it runs, what stands around it). The blocks stay the
 * game's truth; this is only what is drawn in their place:
 * <ul>
 *   <li>the top is one smooth sheet through the levels of the blocks (a spline over the heights at
 *       the blocks' corners) instead of a tilted square per block;</li>
 *   <li>where lava ends in the open it ends in a rounded front that comes down to the floor,
 *       instead of a wall;</li>
 *   <li>against glass and other things that are not full blocks it stands flush, with a side face;
 *       a column of lava under more lava (a lava-fall) has plain sides;</li>
 *   <li>every corner of the surface carries how hot the lava is there, which way and how fast it
 *       runs, how far it is from the edge and the light of the sky on it, for the shader.</li>
 * </ul>
 * A block of a calm lake is one square; only where the surface bends is it cut finer, and the two
 * meet without gaps (a calm block next to a bent one is cut into a fan that shares the finer edge).
 *
 * <p>No game classes here: the game's side fills {@link Cells} and draws the {@link Mesh}; a
 * program without the game can do the same (the build checks the surface for gaps that way).
 * One instance per thread.
 */
public final class LavaMesher {
    /** Blocks of the surroundings known on each side of the section, and the block below and above it. */
    public static final int MARGIN = 3;
    public static final int WIDE = 16 + 2 * MARGIN, TALL = 18;
    /** What a block is to the lava: nothing in the way, something in the way that does not hide it (glass, a slab), a full opaque block, lava. */
    public static final byte OPEN = 0, WALL = 1, SOLID = 2, LAVA = 3;
    /** {@link Cells#flags}: the lava is falling. */
    public static final int FALLING = 1;

    /** Where the surface of a block that bends is cut, across the block: close together by the edges, for the rounded front. */
    public static final float[] LINES = { 0.0F, 0.07F, 0.25F, 0.5F, 0.75F, 0.93F, 1.0F };
    public static final int FINE = LINES.length - 1;
    /** Corners of the finest cut across a section. */
    public static final int ACROSS = 16 * FINE + 1;
    /** How far from where it ends in the open the lava starts to come down, in blocks. */
    public static final float FRONT = 0.3F;

    /** Numbers a corner: x, the height it is going to, z, light and kind and heat and edge; the height it comes from, when it set off; flow; which way the surface faces. */
    public static final int VERTEX_FLOATS = 8;
    /** Kinds of face. */
    public static final int TOP = 0, SIDE = 1, UNDER = 2, FALL = 3;

    /** What is known about the section and its surroundings. Places: x and z from {@code -MARGIN} to {@code 15 + MARGIN}, y from -1 to 16. */
    public static final class Cells {
        public final byte[] kind = new byte[WIDE * TALL * WIDE];
        public final byte[] flags = new byte[WIDE * TALL * WIDE];
        /** Of lava: how high it stands in its block by itself, 0..1 (8/9 for a block that is full). */
        public final float[] height = new float[WIDE * TALL * WIDE];
        /** Of lava: which way it runs (a direction, or nothing). */
        public final float[] flowX = new float[WIDE * TALL * WIDE], flowZ = new float[WIDE * TALL * WIDE];
        /** Of lava: the light of the sky on it, 0..15. */
        public final byte[] sky = new byte[WIDE * TALL * WIDE];

        public static int at(int x, int y, int z) {
            return ((y + 1) * WIDE + (z + MARGIN)) * WIDE + (x + MARGIN);
        }

        public void clear() {
            Arrays.fill(kind, OPEN);
            Arrays.fill(flags, (byte) 0);
            Arrays.fill(height, 0.0F);
            Arrays.fill(flowX, 0.0F);
            Arrays.fill(flowZ, 0.0F);
            Arrays.fill(sky, (byte) 0);
        }

        public void lava(int x, int y, int z, float own, boolean falling, float fx, float fz, int skyLight) {
            int c = at(x, y, z);
            kind[c] = LAVA;
            flags[c] = (byte) (falling ? FALLING : 0);
            height[c] = own;
            flowX[c] = fx;
            flowZ[c] = fz;
            sky[c] = (byte) skyLight;
        }
    }

    /** The surface: four corners a face, {@link #VERTEX_FLOATS} numbers a corner, places in blocks from the least corner of the section. */
    public static final class Mesh {
        public float[] data = new float[4 * VERTEX_FLOATS * 64];
        /**
         * For each corner: which point of the top it is ({@code ix + ACROSS * (iz + ACROSS * y)}, the finest cut numbered
         * across the section), or -1 for a corner that never moves. Corners with the same number are the same point.
         */
        public int[] key = new int[4 * 64];
        /** For each corner of the top: how late lava gets there when it is new to its block, 0 (where it comes in) to 1. */
        public float[] delay = new float[4 * 64];
        public int quads;
        /**
         * The faces from this one on are each the whole top of a calm block (a block of a lake that neither bends nor
         * touches one that does): four corners a block apart. Drawn as they are they are flat; close by they are cut finer
         * as they are drawn (along {@link #LINES}, as the bent blocks around them are), so that waves can run over them.
         */
        public int calmFrom;
        public float minY, maxY;
        /** For each column of the section ({@code z * 16 + x}): the height its topmost lava rests at, in blocks from the section's floor; NaN where there is none. */
        public final float[] rests = new float[256];

        void room(int moreQuads) {
            int need = (quads + moreQuads) * 4;
            if (need > key.length) {
                int to = Math.max(need, key.length * 2);
                key = Arrays.copyOf(key, to);
                delay = Arrays.copyOf(delay, to);
                data = Arrays.copyOf(data, to * VERTEX_FLOATS);
            }
        }
    }

    // ---- per section
    private Cells cells;
    private Mesh mesh;
    /** The faces of the blocks whose top is one calm face: put after all the others when the section is done. */
    private Mesh calm;
    /** Corners of blocks, x and z from {@code 1 - MARGIN} to {@code 15 + MARGIN}: height (NaN: no lava around it), heat, flow, light. */
    private static final int CORNERS = WIDE - 1;
    private final float[] cornerHeight = new float[CORNERS * CORNERS], cornerHeat = new float[CORNERS * CORNERS];
    private final float[] cornerFlowX = new float[CORNERS * CORNERS], cornerFlowZ = new float[CORNERS * CORNERS], cornerSky = new float[CORNERS * CORNERS];
    private final boolean[] cornerKnown = new boolean[CORNERS * CORNERS];
    /** Blocks from -1 to 16: 0 not looked at, 1 calm, 2 bent, 3 no top here. */
    private final byte[] bent = new byte[18 * 18];
    /** Points of the finest cut in the layer at hand: height (NaN: not worked out), and the rest of what a corner carries. */
    private final float[] pointHeight = new float[ACROSS * ACROSS];
    private final float[] pointA = new float[ACROSS * ACROSS], pointB = new float[ACROSS * ACROSS], pointC = new float[ACROSS * ACROSS];
    private final float[] spline = new float[16];
    private final int[] loop = new int[4 * FINE + 4];
    private int layer;

    /** The surface of the lava in the section {@code cells} describes; with no lava to be seen, a mesh of no faces. */
    public Mesh build(Cells cells) {
        this.cells = cells;
        Mesh out = new Mesh();
        this.mesh = out;
        out.minY = Float.MAX_VALUE;
        out.maxY = -Float.MAX_VALUE;
        Arrays.fill(out.rests, Float.NaN);
        calm = new Mesh();
        calm.minY = Float.MAX_VALUE;
        calm.maxY = -Float.MAX_VALUE;
        for (int y = 0; y < 16; y++) {
            boolean any = false, tops = false;
            for (int z = 0; z < 16 && !tops; z++) {
                for (int x = 0; x < 16; x++) {
                    if (cells.kind[Cells.at(x, y, z)] != LAVA) continue;
                    any = true;
                    if (cells.kind[Cells.at(x, y + 1, z)] != LAVA) { tops = true; break; }
                }
            }
            if (!any) continue;
            layer = y;
            Arrays.fill(cornerKnown, false);
            Arrays.fill(bent, (byte) 0);
            if (tops) Arrays.fill(pointHeight, Float.NaN);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (cells.kind[Cells.at(x, y, z)] != LAVA) continue;
                    boolean top = cells.kind[Cells.at(x, y + 1, z)] != LAVA;
                    if (top) {
                        top(x, z);
                        out.rests[z * 16 + x] = y + cells.height[Cells.at(x, y, z)];
                    }
                    sides(x, z, top);
                    byte under = cells.kind[Cells.at(x, y - 1, z)];
                    if (under == OPEN || under == WALL) under(x, z);
                }
            }
        }
        // the calm blocks last: each one face
        out.calmFrom = out.quads;
        out.room(calm.quads);
        System.arraycopy(calm.data, 0, out.data, out.quads * 4 * VERTEX_FLOATS, calm.quads * 4 * VERTEX_FLOATS);
        System.arraycopy(calm.key, 0, out.key, out.quads * 4, calm.quads * 4);
        System.arraycopy(calm.delay, 0, out.delay, out.quads * 4, calm.quads * 4);
        out.quads += calm.quads;
        out.minY = Math.min(out.minY, calm.minY);
        out.maxY = Math.max(out.maxY, calm.maxY);
        if (out.quads == 0) { out.minY = 0.0F; out.maxY = 0.0F; }
        this.cells = null;
        this.mesh = null;
        this.calm = null;
        return out;
    }

    // ------------------------------------------------------------------ what the blocks say

    private byte kind(int x, int z) {
        return cells.kind[Cells.at(x, layer, z)];
    }

    /** Lava with no lava on top of it. */
    private boolean hasTop(int x, int z) {
        return cells.kind[Cells.at(x, layer, z)] == LAVA && cells.kind[Cells.at(x, layer + 1, z)] != LAVA;
    }

    /** How hot the lava of a block is taken to be, 0..1: a lake that stands still has grown a crust, lava on the run has torn it, falling lava has none. */
    private float heat(int c) {
        if ((cells.flags[c] & FALLING) != 0) return 1.0F;
        boolean runs = cells.flowX[c] != 0.0F || cells.flowZ[c] != 0.0F;
        float h = cells.height[c];
        if (h >= 0.8F) return runs ? 0.82F : 0.42F;
        return 0.72F + 0.26F * h;
    }

    /**
     * The corner four blocks meet at ({@code cx, cz}: the least corner of the block there). Its height is that of the
     * game's own lava at such a corner: full if one of the four has lava on top, otherwise the mean of the lava around
     * it (full blocks count ten times) and of the open blocks around it (as nothing).
     */
    private int corner(int cx, int cz) {
        int at = (cz + MARGIN - 1) * CORNERS + (cx + MARGIN - 1);
        if (cornerKnown[at]) return at;
        cornerKnown[at] = true;
        float sum = 0.0F, weight = 0.0F, heat = 0.0F, fx = 0.0F, fz = 0.0F, sky = 0.0F;
        int lava = 0;
        boolean full = false;
        for (int k = 0; k < 4; k++) {
            int x = cx - (k & 1), z = cz - (k >> 1);
            int c = Cells.at(x, layer, z);
            byte kind = cells.kind[c];
            if (kind == LAVA) {
                lava++;
                heat += heat(c);
                fx += cells.flowX[c];
                fz += cells.flowZ[c];
                sky += cells.sky[c];
                if (cells.kind[Cells.at(x, layer + 1, z)] == LAVA) { full = true; continue; }
                float h = cells.height[c];
                if (h >= 0.8F) { sum += h * 10.0F; weight += 10.0F; } else { sum += h; weight += 1.0F; }
            } else if (kind == OPEN) {
                weight += 1.0F;
            }
        }
        if (lava == 0) {
            cornerHeight[at] = Float.NaN;
        } else {
            cornerHeight[at] = full ? 1.0F : sum / weight;
            cornerHeat[at] = heat / lava;
            cornerFlowX[at] = fx / lava;
            cornerFlowZ[at] = fz / lava;
            cornerSky[at] = sky / lava;
        }
        return at;
    }

    /** The sixteen corners around a block, for the spline; a corner with no lava around it stands in as the block's own nearest one. */
    private void around(int bx, int bz) {
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                float h = cornerHeight[corner(bx - 1 + i, bz - 1 + j)];
                if (h != h) h = cornerHeight[corner(bx + Math.min(Math.max(i, 1), 2) - 1, bz + Math.min(Math.max(j, 1), 2) - 1)];
                spline[j * 4 + i] = h;
            }
        }
    }

    private static float along(float p0, float p1, float p2, float p3, float t) {
        return p1 + 0.5F * t * (p2 - p0 + t * (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3 + t * (3.0F * (p1 - p2) + p3 - p0)));
    }

    /** How far a point is from the nearest open block among those around the block it is in (or on the edge of); 9 if there is none. */
    private float away(float px, float pz) {
        int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
        float least = 9.0F;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = bx + dx, z = bz + dz;
                if (x < -MARGIN || x > 15 + MARGIN || z < -MARGIN || z > 15 + MARGIN) continue;
                if (kind(x, z) != OPEN) continue;
                float ax = px < x ? x - px : px > x + 1 ? px - (x + 1) : 0.0F, az = pz < z ? z - pz : pz > z + 1 ? pz - (z + 1) : 0.0F;
                float d = (float) Math.sqrt(ax * ax + az * az);
                if (d < least) least = d;
            }
        }
        return least;
    }

    /** How much of its height the lava keeps this far from where it ends in the open: a quarter of a circle. */
    private static float front(float away) {
        if (away >= FRONT) return 1.0F;
        float t = 1.0F - away / FRONT;
        return (float) Math.sqrt(1.0F - t * t);
    }

    /** The height of the top over a point of the block {@code bx, bz} (a block with a top), in blocks from the floor of the layer. */
    private float over(int bx, int bz, float px, float pz) {
        around(bx, bz);
        float u = px - bx, v = pz - bz;
        float[] s = spline;
        float h = along(along(s[0], s[1], s[2], s[3], u), along(s[4], s[5], s[6], s[7], u), along(s[8], s[9], s[10], s[11], u), along(s[12], s[13], s[14], s[15], u), v);
        h = Math.min(1.0F, Math.max(0.0F, h));
        return h * front(away(px, pz));
    }

    /** The same anywhere in the layer: nothing over an open block, full under more lava, and no answer (NaN) where something stands. */
    private float surface(float px, float pz) {
        int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
        if (bx < -1 || bx > 16 || bz < -1 || bz > 16) return Float.NaN;
        byte kind = kind(bx, bz);
        if (kind == OPEN) return 0.0F;
        if (kind != LAVA) return Float.NaN;
        if (!hasTop(bx, bz)) return 1.0F;
        return over(bx, bz, px, pz);
    }

    /** Whether the top of a block bends (it ends in the open nearby, or the corners around it are not all at one height). */
    private boolean bent(int bx, int bz) {
        int at = (bz + 1) * 18 + (bx + 1);
        byte known = bent[at];
        if (known == 0) {
            known = 3;
            if (hasTop(bx, bz)) {
                known = 1;
                search:
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (kind(bx + dx, bz + dz) == OPEN) { known = 2; break search; }
                    }
                }
                if (known == 1) {
                    around(bx, bz);
                    for (int k = 1; k < 16; k++) {
                        if (Math.abs(spline[k] - spline[0]) > 1.0e-5F) { known = 2; break; }
                    }
                }
            }
            bent[at] = known;
        }
        return known == 2;
    }

    // ------------------------------------------------------------------ the top

    /** The point {@code i, j} of the finest cut of the block {@code bx, bz}, worked out once whichever block asks first. */
    private int point(int bx, int bz, int i, int j) {
        int at = (bz * FINE + j) * ACROSS + bx * FINE + i;
        if (pointHeight[at] == pointHeight[at]) return at;
        float u = LINES[i], v = LINES[j], px = bx + u, pz = bz + v;
        float h = over(bx, bz, px, pz);
        pointHeight[at] = h;

        // which way the surface faces there, from the heights a little to each side
        final float e = 0.03F;
        float x1 = surface(px + e, pz), x0 = surface(px - e, pz), z1 = surface(px, pz + e), z0 = surface(px, pz - e);
        float dx = x1 == x1 && x0 == x0 ? (x1 - x0) / (2.0F * e) : x1 == x1 ? (x1 - h) / e : x0 == x0 ? (h - x0) / e : 0.0F;
        float dz = z1 == z1 && z0 == z0 ? (z1 - z0) / (2.0F * e) : z1 == z1 ? (z1 - h) / e : z0 == z0 ? (h - z0) / e : 0.0F;
        float length = (float) Math.sqrt(dx * dx + dz * dz + 1.0F);
        pointC[at] = packNormal(-dx / length, -dz / length);

        int c00 = corner(bx, bz), c10 = corner(bx + 1, bz), c01 = corner(bx, bz + 1), c11 = corner(bx + 1, bz + 1);
        float w00 = (1.0F - u) * (1.0F - v), w10 = u * (1.0F - v), w01 = (1.0F - u) * v, w11 = u * v;
        float heat = cornerHeat[c00] * w00 + cornerHeat[c10] * w10 + cornerHeat[c01] * w01 + cornerHeat[c11] * w11;
        float fx = cornerFlowX[c00] * w00 + cornerFlowX[c10] * w10 + cornerFlowX[c01] * w01 + cornerFlowX[c11] * w11;
        float fz = cornerFlowZ[c00] * w00 + cornerFlowZ[c10] * w10 + cornerFlowZ[c01] * w01 + cornerFlowZ[c11] * w11;
        float sky = cornerSky[c00] * w00 + cornerSky[c10] * w10 + cornerSky[c01] * w01 + cornerSky[c11] * w11;
        float edge = Math.min(1.0F, away(px, pz));
        pointA[at] = packLook(sky, TOP, heat, edge, Math.min(1.0F, h));
        pointB[at] = packFlow(fx, fz);
        return at;
    }

    /** {@code thick}: how thick the lava is at a corner that belongs to the top's sheet, 0..1 (waves move it by that much); -1 for a corner that stays put. */
    private static float packLook(float sky, int kind, float heat, float edge, float thick) {
        int s = Math.min(15, Math.max(0, Math.round(sky)));
        int h = Math.min(63, Math.max(0, Math.round(heat * 63.0F)));
        int e = Math.min(15, Math.max(0, Math.round(edge * 15.0F)));
        int t = thick < 0.0F ? 0 : 1 << 23 | Math.min(7, Math.round(thick * 7.0F)) << 20;
        return (float) (s | 15 << 4 | kind << 8 | h << 10 | e << 16 | t);
    }

    private static float packFlow(float fx, float fz) {
        int x = Math.min(255, Math.max(0, Math.round((fx * 0.5F + 0.5F) * 255.0F))), z = Math.min(255, Math.max(0, Math.round((fz * 0.5F + 0.5F) * 255.0F)));
        return (float) (x | z << 8);
    }

    private static float packNormal(float nx, float nz) {
        int x = Math.min(4095, Math.max(0, Math.round((nx * 0.5F + 0.5F) * 4095.0F))), z = Math.min(4095, Math.max(0, Math.round((nz * 0.5F + 0.5F) * 4095.0F)));
        return (float) (x | z << 12);
    }

    /** One corner of a face of the top: a point of the finest cut. */
    private void put(int v, int at, float lateness) {
        float[] d = mesh.data;
        int o = v * VERTEX_FLOATS;
        int ix = at % ACROSS, iz = at / ACROSS;
        float y = layer + Math.max(0.004F, pointHeight[at]);
        d[o] = ix / FINE + LINES[ix % FINE];
        d[o + 1] = y;
        d[o + 2] = iz / FINE + LINES[iz % FINE];
        d[o + 3] = pointA[at];
        d[o + 4] = y;
        d[o + 5] = -1.0e6F;
        d[o + 6] = pointB[at];
        d[o + 7] = pointC[at];
        mesh.key[v] = at + ACROSS * ACROSS * layer;
        mesh.delay[v] = lateness;
        if (y < mesh.minY) mesh.minY = y;
        if (y > mesh.maxY) mesh.maxY = y;
    }

    private void top(int bx, int bz) {
        int c = Cells.at(bx, layer, bz);
        float fx = cells.flowX[c], fz = cells.flowZ[c];
        boolean runs = fx != 0.0F || fz != 0.0F;
        if (bent(bx, bz)) {
            mesh.room(FINE * FINE);
            for (int j = 0; j < FINE; j++) {
                for (int i = 0; i < FINE; i++) {
                    int v = mesh.quads * 4;
                    for (int k = 0; k < 4; k++) {
                        int pi = i + (k >> 1), pj = j + ((k + 1 >> 1) & 1);
                        put(v + k, point(bx, bz, pi, pj), runs ? late(LINES[pi], LINES[pj], fx, fz) : 0.0F);
                    }
                    mesh.quads++;
                }
            }
            return;
        }
        // a calm block: one square (kept aside and put after all the other faces, see build), or, where a neighbour's top bends, a fan around the
        // middle whose rim is cut as fine as the bent block's edge all the way round (so that it also fits a calm
        // neighbour that is cut finer as it is drawn)
        if (!bent(bx, bz - 1) && !bent(bx, bz + 1) && !bent(bx - 1, bz) && !bent(bx + 1, bz)) {
            Mesh all = mesh;
            mesh = calm;
            calm.room(1);
            int v = calm.quads * 4;
            put(v, point(bx, bz, 0, 0), runs ? late(0.0F, 0.0F, fx, fz) : 0.0F);
            put(v + 1, point(bx, bz, 0, FINE), runs ? late(0.0F, 1.0F, fx, fz) : 0.0F);
            put(v + 2, point(bx, bz, FINE, FINE), runs ? late(1.0F, 1.0F, fx, fz) : 0.0F);
            put(v + 3, point(bx, bz, FINE, 0), runs ? late(1.0F, 0.0F, fx, fz) : 0.0F);
            calm.quads++;
            mesh = all;
            return;
        }
        int n = 0;
        // around the block: along the west edge southwards, the south edge eastwards, the east edge northwards, the north edge westwards
        for (int j = 0; j < FINE; j++) loop[n++] = j * 16;
        for (int i = 0; i < FINE; i++) loop[n++] = FINE * 16 + i;
        for (int j = FINE; j > 0; j--) loop[n++] = j * 16 + FINE;
        for (int i = FINE; i > 0; i--) loop[n++] = i;
        mesh.room(n);
        int middle = point(bx, bz, FINE / 2, FINE / 2);
        float middleLate = runs ? late(0.5F, 0.5F, fx, fz) : 0.0F;
        for (int k = 0; k < n; k++) {
            int a = loop[k], b = loop[(k + 1) % n];
            int v = mesh.quads * 4;
            put(v, middle, middleLate);
            put(v + 1, point(bx, bz, a & 15, a >> 4), runs ? late(LINES[a & 15], LINES[a >> 4], fx, fz) : 0.0F);
            int pb = point(bx, bz, b & 15, b >> 4);
            float lateB = runs ? late(LINES[b & 15], LINES[b >> 4], fx, fz) : 0.0F;
            put(v + 2, pb, lateB);
            put(v + 3, pb, lateB);
            mesh.quads++;
        }
    }

    /** How late lava that is new to a block gets to a point of it: it comes in from where it runs from. */
    private static float late(float u, float v, float fx, float fz) {
        return Math.min(1.0F, Math.max(0.0F, 0.5F + (u - 0.5F) * fx + (v - 0.5F) * fz));
    }

    // ------------------------------------------------------------------ sides and the underside

    /** A corner that does not belong to the top's sheet. */
    private void fixed(int v, float x, float y, float z, float look, float flow, float normal) {
        float[] d = mesh.data;
        int o = v * VERTEX_FLOATS;
        d[o] = x; d[o + 1] = y; d[o + 2] = z; d[o + 3] = look;
        d[o + 4] = y; d[o + 5] = -1.0e6F; d[o + 6] = flow; d[o + 7] = normal;
        mesh.key[v] = -1;
        mesh.delay[v] = 0.0F;
        if (y < mesh.minY) mesh.minY = y;
        if (y > mesh.maxY) mesh.maxY = y;
    }

    /** A corner on the top's sheet that belongs to a side: it moves with the sheet, but is lit and drawn as the side. */
    private void hung(int v, int at, float x, float z, float look, float flow, float normal) {
        put(v, at, 0.0F);
        float[] d = mesh.data;
        int o = v * VERTEX_FLOATS;
        // (it keeps the top's word on how much the waves move it)
        d[o] = x; d[o + 2] = z; d[o + 3] = look + (float) ((int) d[o + 3] & 15 << 20); d[o + 6] = flow; d[o + 7] = normal;
    }

    private static final int[] SIDE_X = { 0, 0, -1, 1 }, SIDE_Z = { -1, 1, 0, 0 };

    private void sides(int bx, int bz, boolean top) {
        int c = Cells.at(bx, layer, bz);
        for (int side = 0; side < 4; side++) {
            int nx = bx + SIDE_X[side], nz = bz + SIDE_Z[side];
            byte next = kind(nx, nz);
            if (next == LAVA || next == SOLID) continue;
            // in the open the top comes down to the floor by itself; only lava under more lava shows a side there
            if (next == OPEN && top) continue;
            boolean falling = (cells.flags[c] & FALLING) != 0;
            float look = packLook(cells.sky[c], falling ? FALL : SIDE, heat(c), next == OPEN ? 1.0F : 0.0F, -1.0F);
            float flow = packFlow(cells.flowX[c], cells.flowZ[c]);
            float normal = packNormal(SIDE_X[side], SIDE_Z[side]);
            // the face, a hair inside the block; a and b: its two ends, seen from outside left to right is not needed (drawn from both sides)
            final float in = 0.001F;
            float ax, az, bxx, bzz;
            switch (side) {
                case 0 -> { ax = bx; az = bz + in; bxx = bx + 1; bzz = bz + in; }
                case 1 -> { ax = bx; az = bz + 1 - in; bxx = bx + 1; bzz = bz + 1 - in; }
                case 2 -> { ax = bx + in; az = bz; bxx = bx + in; bzz = bz + 1; }
                default -> { ax = bx + 1 - in; az = bz; bxx = bx + 1 - in; bzz = bz + 1; }
            }
            float floor = layer + (cells.kind[Cells.at(bx, layer - 1, bz)] == LAVA ? 0.0F : 0.001F);
            if (!top) {
                mesh.room(1);
                int v = mesh.quads * 4;
                fixed(v, ax, layer + 1.0F, az, look, flow, normal);
                fixed(v + 1, bxx, layer + 1.0F, bzz, look, flow, normal);
                fixed(v + 2, bxx, floor, bzz, look, flow, normal);
                fixed(v + 3, ax, floor, az, look, flow, normal);
                mesh.quads++;
                continue;
            }
            // flush against something that does not hide it: from the floor up to the top's own edge, cut where the top is cut
            boolean fine = bent(bx, bz) || bent(nx, nz);
            int step = fine ? 1 : FINE;
            mesh.room(FINE / step);
            for (int k = 0; k < FINE; k += step) {
                int i0, j0, i1, j1;
                switch (side) {
                    case 0 -> { i0 = k; j0 = 0; i1 = k + step; j1 = 0; }
                    case 1 -> { i0 = k; j0 = FINE; i1 = k + step; j1 = FINE; }
                    case 2 -> { i0 = 0; j0 = k; i1 = 0; j1 = k + step; }
                    default -> { i0 = FINE; j0 = k; i1 = FINE; j1 = k + step; }
                }
                float t0 = LINES[k], t1 = LINES[k + step];
                float x0 = ax + (bxx - ax) * t0, z0 = az + (bzz - az) * t0, x1 = ax + (bxx - ax) * t1, z1 = az + (bzz - az) * t1;
                int v = mesh.quads * 4;
                hung(v, point(bx, bz, i0, j0), x0, z0, look, flow, normal);
                hung(v + 1, point(bx, bz, i1, j1), x1, z1, look, flow, normal);
                fixed(v + 2, x1, floor, z1, look, flow, normal);
                fixed(v + 3, x0, floor, z0, look, flow, normal);
                mesh.quads++;
            }
        }
    }

    private void under(int bx, int bz) {
        int c = Cells.at(bx, layer, bz);
        float look = packLook(cells.sky[c], UNDER, heat(c), 0.5F, -1.0F), flow = packFlow(cells.flowX[c], cells.flowZ[c]), normal = packNormal(0.0F, 0.0F);
        float y = layer + 0.001F;
        mesh.room(1);
        int v = mesh.quads * 4;
        fixed(v, bx, y, bz, look, flow, normal);
        fixed(v + 1, bx + 1, y, bz, look, flow, normal);
        fixed(v + 2, bx + 1, y, bz + 1, look, flow, normal);
        fixed(v + 3, bx, y, bz + 1, look, flow, normal);
        mesh.quads++;
    }
}
