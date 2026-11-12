package physicsbruhh.client;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.event.RegisterFluidModelsEvent;
import net.neoforged.neoforge.client.fluid.CustomFluidRenderer;
import physicsbruhh.PhysicsBruhh;
import physicsbruhh.sim.LavaMesher;

/**
 * Where the mod draws the lava ({@link LavaClient}), the game's own picture of it is left out, and
 * the mod's is made in its place, at the same moment and from the same blocks:
 * <ul>
 *   <li>the game asks a fluid's model, when it builds a chunk section's geometry, whether somebody
 *       else draws that fluid there; for lava the mod says yes while its own lava is on;</li>
 *   <li>when the game has gone through a section, the mod looks at the same snapshot of the blocks
 *       the game built from and makes its surface of the lava in it ({@link LavaMesher}).</li>
 * </ul>
 * So whenever a block changes and the game builds the section afresh, the mod's surface follows,
 * with nothing to watch and nothing to scan. Both happen on the threads that build chunks. The
 * lava itself is not touched: it flows, burns, lights and makes stone as it always did, on a
 * server that knows nothing of this.
 */
final class LavaHide {
    private LavaHide() {}

    /** The game's lava models ask the mod now (if not, the game's own lava stays). */
    static volatile boolean hooked;

    /** What a thread that builds chunks keeps between two calls: which section it is in and how much lava it has met there. */
    private static final class Worker {
        long section = Long.MIN_VALUE;
        int met;
        final LavaMesher.Cells cells = new LavaMesher.Cells();
        final LavaMesher mesher = new LavaMesher();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    }

    private static final ThreadLocal<Worker> WORKER = ThreadLocal.withInitial(Worker::new);

    @SuppressWarnings("unchecked")
    static void models(RegisterFluidModelsEvent event) {
        try {
            Map<Fluid, FluidModel> models = null;
            for (Field field : RegisterFluidModelsEvent.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                models = (Map<Fluid, FluidModel>) field.get(event);
            }
            if (models == null) throw new IllegalStateException("the event does not hold the game's fluid models");
            int wrapped = 0;
            for (Fluid fluid : List.of(Fluids.LAVA, Fluids.FLOWING_LAVA)) {
                FluidModel model = models.get(fluid);
                if (model == null) continue;
                final CustomFluidRenderer usual = model.customRenderer();
                models.put(fluid, new FluidModel(model.layer(), model.stillMaterial(), model.flowingMaterial(), model.overlayMaterial(), model.fluidTintSource(),
                        (renderer, state, region, pos, output, block) -> {
                            if (LavaClient.active) { met(pos); return true; }
                            return usual != null && usual.renderFluid(renderer, state, region, pos, output, block);
                        }));
                wrapped++;
            }
            hooked = wrapped == 2;
            PhysicsBruhh.LOGGER.info("Lava: {} of the game's 2 lava models leave the drawing to the mod where its lava is on", wrapped);
        } catch (Throwable t) {
            hooked = false;
            PhysicsBruhh.LOGGER.warn("Lava: the game's own lava cannot be left out, so the mod's lava stays off ({})", t.toString());
        }
    }

    /** A block of lava the game would have drawn, in the section the thread is building. */
    private static void met(BlockPos pos) {
        Worker worker = WORKER.get();
        long section = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        if (worker.section != section) { worker.section = section; worker.met = 0; }
        worker.met++;
    }

    /** Every section the game is about to build: when it is through, the mod has its turn. */
    static void sections(AddSectionGeometryEvent event) {
        if (!LavaClient.active) return;
        BlockPos origin = event.getSectionOrigin();
        final int sx = origin.getX() >> 4, sy = origin.getY() >> 4, sz = origin.getZ() >> 4;
        event.addRenderer(context -> built(context.getRegion(), sx, sy, sz));
    }

    private static void built(BlockAndTintGetter region, int sx, int sy, int sz) {
        Worker worker = WORKER.get();
        long section = SectionPos.asLong(sx, sy, sz);
        boolean lava = worker.section == section && worker.met > 0;
        worker.section = Long.MIN_VALUE;
        worker.met = 0;
        if (!LavaClient.active) return;
        if (!lava) { LavaClient.arrived(section, LavaClient.NONE); return; }
        try {
            look(worker, region, sx << 4, sy << 4, sz << 4);
            LavaMesher.Mesh mesh = worker.mesher.build(worker.cells);
            LavaClient.arrived(section, mesh.quads == 0 ? LavaClient.NONE : mesh);
        } catch (Throwable t) {
            // lava that is neither the game's nor the mod's would be lava nobody sees: the mod's goes off, the game's comes back
            LavaClient.failed("making the surface of a section", t);
        }
    }

    /** What the blocks of the section and around it are to the lava. */
    private static void look(Worker worker, BlockAndTintGetter region, int ox, int oy, int oz) {
        LavaMesher.Cells cells = worker.cells;
        BlockPos.MutableBlockPos pos = worker.pos;
        cells.clear();
        final int m = LavaMesher.MARGIN;
        for (int y = -1; y <= 16; y++) {
            for (int z = -m; z < 16 + m; z++) {
                for (int x = -m; x < 16 + m; x++) {
                    pos.set(ox + x, oy + y, oz + z);
                    BlockState state = region.getBlockState(pos);
                    if (state.isAir()) continue;
                    int c = LavaMesher.Cells.at(x, y, z);
                    FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) {
                        if (fluid.getType().isSame(Fluids.LAVA)) {
                            cells.kind[c] = LavaMesher.LAVA;
                            cells.height[c] = fluid.getOwnHeight();
                            if (fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING)) cells.flags[c] = LavaMesher.FALLING;
                        } else {
                            cells.kind[c] = LavaMesher.WALL;
                        }
                    } else if (state.isSolidRender()) {
                        cells.kind[c] = LavaMesher.SOLID;
                    } else if (!state.getCollisionShape(region, pos).isEmpty()) {
                        cells.kind[c] = LavaMesher.WALL;
                    }
                }
            }
        }
        // of the lava that has a top: which way it runs, and the light of the sky on it
        for (int y = 0; y <= 15; y++) {
            for (int z = -m; z < 16 + m; z++) {
                for (int x = -m; x < 16 + m; x++) {
                    int c = LavaMesher.Cells.at(x, y, z);
                    if (cells.kind[c] != LavaMesher.LAVA) continue;
                    boolean top = cells.kind[LavaMesher.Cells.at(x, y + 1, z)] != LavaMesher.LAVA;
                    pos.set(ox + x, oy + y, oz + z);
                    if (top) {
                        Vec3 flow = region.getFluidState(pos).getFlow(region, pos);
                        double length = Math.sqrt(flow.x * flow.x + flow.z * flow.z);
                        if (length > 1.0e-4) { cells.flowX[c] = (float) (flow.x / length); cells.flowZ[c] = (float) (flow.z / length); }
                        pos.setY(oy + y + 1);
                    }
                    cells.sky[c] = (byte) region.getBrightness(LightLayer.SKY, pos);
                }
            }
        }
    }
}
