package physicsbruhh.client;

/**
 * The surface of the lava as a heavy liquid: what pushes it (a bubble coming up and bursting,
 * something falling in or wading through) is collected here for the solver of its waves.
 */
final class LavaWaves {
    private LavaWaves() {}

    /** Pushes waiting for the next step: x, z, how wide (blocks), how hard (blocks a second, up or down). */
    private static final float[] PUSHES = new float[16 * 4];
    private static double originX, originZ;
    private static int pushes;
    static volatile int pushed;

    /** Something pushes the surface at a place: up (a bubble swelling) or down (a bubble gone, something falling in). */
    static void push(double x, double z, float wide, float hard) {
        pushed++;
        if (pushes >= 16) return;
        if (pushes == 0) { originX = Math.floor(x); originZ = Math.floor(z); }
        int o = pushes++ * 4;
        PUSHES[o] = (float) (x - originX); PUSHES[o + 1] = (float) (z - originZ); PUSHES[o + 2] = wide; PUSHES[o + 3] = hard;
    }

    static void clear() {
        pushes = 0;
    }
}
