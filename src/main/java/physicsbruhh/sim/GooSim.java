package physicsbruhh.sim;

import java.nio.ByteBuffer;

/**
 * The goo simulation as the mod sees it. Two implementations exist: {@link NativeSim} (Rust, via
 * JNI) and {@link JavaSim} (the same algorithm in plain Java, used when the native library is
 * missing or fails). All buffers are direct and in native byte order. Coordinates are local to an
 * origin chosen by the caller. A sim instance must only be used from one thread at a time.
 */
public interface GooSim extends AutoCloseable {
    int HONEY = 0;
    int SLIME = 1;
    /**
     * Lava that has left its block: thick, heavy, slow. It is not counted by {@link #probe} (what it
     * does to a creature is not what goo does), and its particles say more about themselves, see
     * {@link #particles}.
     */
    int LAVA = 2;
    /** How many materials there are. */
    int MATERIALS = 3;
    int STATIC_FLOATS = 7;      // min xyz, max xyz, stick
    int KINEMATIC_FLOATS = 12;  // id (> 0 piston part or moving block, < 0 creature), min xyz, max xyz, velocity xyz, stick, front (0 none, 1..6 = +x -x +y -y +z -z)
    int FLOATS_PER_QUAD = 24;   // 4 * (position xyz + normal xyz)
    /** Particles in a whole block of goo, in a bottle of honey (four to the block) and in a slime ball (nine to the block). */
    int BLOCK = 1728, BOTTLE = 432, BALL = 192;
    int BODY_FLOATS = 8;        // state, awake, bounds min xyz, bounds max xyz
    int PART_FLOATS = 8;        // first quad, number of quads, then the box the puddle lies in: least xyz, greatest xyz

    /**
     * How thick a piece of goo may be, as a factor on the usual (1). Thick honey melts later, flows
     * more slowly and holds a heap, thin honey runs like syrup; thick slime is a stiff jelly, thin
     * slime soft and saggy. Every piece has its own.
     */
    float THICK_MIN = 0.25F, THICK_MAX = 4.0F;

    /** Adds a body filling the block cell whose minimum corner is (x, y, z), as thick as {@code thick} says. Returns its slot or -1. */
    int addBody(int material, float x, float y, float z, float dirX, float dirZ, float ageSeconds, float thick);

    default int addBody(int material, float x, float y, float z, float dirX, float dirZ, float ageSeconds) { return addBody(material, x, y, z, dirX, dirZ, ageSeconds, 1.0F); }

    /** Adds a body with nothing in it yet at the block cell (x, y, z); goo arrives through {@link #emit}. Returns its slot or -1. */
    int addEmpty(int material, float x, float y, float z, float thick);

    default int addEmpty(int material, float x, float y, float z) { return addEmpty(material, x, y, z, 1.0F); }

    /** Adds {@code count} particles already lying at rest in the cell (x, y, z): a piece that comes back into view. Returns its slot or -1. */
    int addResting(int material, float x, float y, float z, int count, float thick);

    default int addResting(int material, float x, float y, float z, int count) { return addResting(material, x, y, z, count, 1.0F); }

    /**
     * Lets {@code count} more particles into a body, leaving the point p with velocity v: as a stream
     * that comes out over the next steps, or ({@code lump}) at once as a rounded lump. Returns how
     * many the body had room for (it holds one block's worth).
     */
    int emit(int body, float px, float py, float pz, float vx, float vy, float vz, int count, boolean lump);

    /** Removes the {@code count} particles of a body nearest to a point; returns how many were removed. */
    int take(int body, float x, float y, float z, int count);

    void removeBody(int body, boolean fade);

    void wake(int body);

    void setKillY(float y);

    void setStatic(ByteBuffer boxes, int count);

    void setKinematic(ByteBuffer boxes, int count);

    /** Advances by real time {@code dt}; returns how many bodies are awake. */
    int step(float dt);

    /** Writes quads for one material; returns the quad count, or minus the floats needed if {@code out} is too small. */
    int mesh(int material, ByteBuffer out);

    /**
     * Where the puddles of the surface last built by {@link #mesh} for a material are: {@link #PART_FLOATS}
     * floats each, so that whoever draws the quads can leave out the puddles that are not in view.
     * Returns the number of puddles, or minus the floats needed if {@code out} is too small.
     */
    int meshParts(int material, ByteBuffer out);

    /**
     * Writes every particle as four floats: position, then its size (0..1, shrinking while its piece
     * fades away) plus 2 for slime, plus 4 for a filler (a point that only closes a gap along goo
     * pulled long). A particle of lava adds 8, 16 times a pattern (0..63: a blotchy noise over the
     * block the lava came from, which travels and stretches with it) and 1024 times its heat (0..63:
     * what is left of it as the lava cools, more where it is on the move). Returns the number of
     * particles, or minus the floats needed if {@code out} is too small.
     */
    int particles(ByteBuffer out);

    /** Fills {@link #BODY_FLOATS} floats per slot, for as many slots as {@code out} has room for; returns the number of slots there are. */
    int bodies(ByteBuffer out);

    /**
     * Counts the particles inside a box ("is this creature standing in goo"): honey in the low 16 bits,
     * slime in the next 16, each capped at 32767.
     */
    int probe(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);

    /**
     * Diagnostics: {@code what} 0 = particles stuck to the moving box with id {@code arg}, 1 = particles of
     * body {@code arg} stuck to blocks, 2 = fixed steps taken so far (wraps at 2^30), 3 = threads the solver uses,
     * 4 = particles in body {@code arg}, counting those of a stream that are still to come out.
     */
    int count(int what, int arg);

    String backend();

    @Override
    void close();
}
