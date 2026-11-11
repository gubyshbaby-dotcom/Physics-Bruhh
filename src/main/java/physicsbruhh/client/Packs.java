package physicsbruhh.client;

import java.lang.reflect.Method;
import physicsbruhh.PhysicsBruhh;

/**
 * Shader packs. When Iris is installed and a pack is switched on, the pack draws the whole world
 * its own way: several off-screen buffers, its own lighting, shadows cast from the sun. Shaders a
 * mod brings along for geometry in the world know nothing of that and come out wrong or not at
 * all. So while a pack is on, the goo's surface is never drawn with the mod's mesh shader:
 * <ul>
 * <li>goo with the block textures, and clear goo under {@code /goolook shader} or {@code plain},
 * is handed over as ordinary translucent geometry of the game (position, colour, light, normal),
 * which every pack knows how to light (see {@link GooRenderer});
 * <li>clear goo under {@code /goolook liquid} keeps the liquid look ({@link GooFluid}): that one
 * is drawn after the world, over the picture the pack has finished, so it needs nothing from the
 * pack. {@code -Dphysicsbruhh.packs.liquid=false} hands clear goo over as well.
 * </ul>
 * With the pack off, or without Iris, nothing changes.
 *
 * <p>Iris is asked through its public API by reflection, so the mod neither needs it to build nor
 * to run. {@code -Dphysicsbruhh.packs=true} pretends a pack is on and hands everything over (for
 * testing the hand-over without a pack).
 */
final class Packs {
    private Packs() {}

    private static final Object API;
    private static final Method IN_USE;
    private static final boolean PRETEND = Boolean.getBoolean("physicsbruhh.packs");
    /** The liquid look stays on under a pack: it is drawn after the world, on top of what the pack made of it. */
    static final boolean LIQUID_ON_TOP = System.getProperty("physicsbruhh.packs.liquid") == null ? !PRETEND : Boolean.getBoolean("physicsbruhh.packs.liquid");

    /** A shader pack is drawing the world (checked once a tick). */
    static volatile boolean active = PRETEND;

    static {
        Object api = null;
        Method inUse = null;
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = iris.getMethod("getInstance").invoke(null);
            inUse = iris.getMethod("isShaderPackInUse");
            PhysicsBruhh.LOGGER.info("Iris is here: while a shader pack is on, goo is handed to it as ordinary translucent geometry{}", LIQUID_ON_TOP ? " (clear goo as a liquid is drawn over the pack's picture)" : "");
        } catch (Throwable notThere) {
            // no Iris, or an Iris without this API: nothing to adapt to
        }
        API = api;
        IN_USE = inUse;
    }

    static boolean irisPresent() {
        return IN_USE != null;
    }

    static void tick() {
        boolean now = PRETEND;
        if (IN_USE != null) {
            try {
                now |= (Boolean) IN_USE.invoke(API);
            } catch (Throwable t) {
                // an Iris that cannot say: treat as off
            }
        }
        if (now != active) {
            active = now;
            PhysicsBruhh.LOGGER.info(now ? "A shader pack is on: the goo's surface goes through the game's own translucent drawing" : "The shader pack is off: goo is drawn with its own shaders again");
        }
    }
}
