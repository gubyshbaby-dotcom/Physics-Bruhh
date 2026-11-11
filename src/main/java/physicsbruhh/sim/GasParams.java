package physicsbruhh.sim;

/**
 * The numbers of the gas model, in the order both solvers take them: the shaders
 * ({@code shaders/include/gas.glsl}, blocks GasA..GasE) and the Rust core ({@code native/src/gas.rs},
 * {@code Params}, which says what each one is). The core holds its own copy of the defaults;
 * {@code SimCheck} fails when the two differ.
 */
public final class GasParams {
    public static final String[] NAMES = {
            "buoyancy", "weight", "cool", "cool4", "ignite", "burn", "heat", "soot", "smoke_fade", "drag",
            "confine", "swell", "unrest", "rounds", "edge", "most_smoke", "most_heat", "most_fuel" };
    private static final float[] VALUES = {
            16.0F, 0.6F, 0.15F, 6.5F, 0.22F, 4.0F, 2.6F, 0.2F, 0.14F, 0.2F,
            15.0F, 2.0F, 8.0F, 16.0F, 3.0F, 4.0F, 1.5F, 2.0F };
    public static final int COUNT = VALUES.length;
    /** Where the number of rounds of the pressure solve is in the list. */
    public static final int ROUNDS = 13;
    public static final int MOST_SMOKE = 15, MOST_HEAT = 16, MOST_FUEL = 17;

    /** What a block is to the gas (second byte of its four in the map of the world, see {@code gas.rs}). */
    public static final int FIRE = 1, CAMPFIRE = 2, TORCH = 3, OVER_LAVA = 4, CHIMNEY = 5, SOUL = 16;
    /** Numbers a blob takes: centre and radius; fuel, smoke, heat, soul; velocity and how much gas it makes. */
    public static final int BLOB_FLOATS = 12;

    private GasParams() {}

    public static float[] values() {
        return VALUES.clone();
    }
}
