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
    int STATIC_FLOATS = 7;      // min xyz, max xyz, stick
    int KINEMATIC_FLOATS = 12;  // id (> 0 piston part or moving block, < 0 creature), min xyz, max xyz, velocity xyz, stick, front (0 none, 1..6 = +x -x +y -y +z -z)
    int FLOATS_PER_QUAD = 24;   // 4 * (position xyz + normal xyz)
    int BODY_FLOATS = 8;        // state, awake, bounds min xyz, bounds max xyz

    /** Adds a body filling the block cell whose minimum corner is (x, y, z). Returns its slot or -1. */
    int addBody(int material, float x, float y, float z, float dirX, float dirZ, float ageSeconds);

    void removeBody(int body, boolean fade);

    void wake(int body);

    void setKillY(float y);

    void setStatic(ByteBuffer boxes, int count);

    void setKinematic(ByteBuffer boxes, int count);

    /** Advances by real time {@code dt}; returns how many bodies are awake. */
    int step(float dt);

    /** Writes quads for one material; returns the quad count, or minus the floats needed if {@code out} is too small. */
    int mesh(int material, ByteBuffer out);

    /** Fills {@link #BODY_FLOATS} floats per slot; returns the slot count. */
    int bodies(ByteBuffer out);

    /**
     * Counts the particles inside a box ("is this creature standing in goo"): honey in the low 16 bits,
     * slime in the next 16, each capped at 32767.
     */
    int probe(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);

    /** Diagnostics: {@code what} 0 = particles stuck to the moving box with id {@code arg}, 1 = particles of body {@code arg} stuck to blocks. */
    int count(int what, int arg);

    String backend();

    @Override
    void close();
}
