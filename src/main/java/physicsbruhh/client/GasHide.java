package physicsbruhh.client;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleResources;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ExtractBlockScreenEffectEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import physicsbruhh.PhysicsBruhh;

/**
 * Where the gas is drawn, the game's own pictures of the same fire and smoke are left out: the
 * flat flames of a block of fire and of a campfire (the campfire keeps its logs), the puffs of
 * smoke over them, the puff of an explosion, and the flames across the screen of a burning player.
 * Where the gas is not drawn (outside the box around the player, with the game rule off, on a
 * machine where it does not work) all of that is as it always was.
 */
final class GasHide {
    private GasHide() {}

    /** Blocks whose own flames are left out, as packed positions. Read by the threads that build the chunks' geometry: never changed, only replaced. */
    private static volatile LongOpenHashSet hidden = new LongOpenHashSet();
    static volatile int hiddenBlocks, particlesLeftOut;

    static boolean hides(BlockPos pos) {
        LongOpenHashSet set = hidden;
        return !set.isEmpty() && set.contains(pos.asLong());
    }

    /** The blocks to leave the flames out of from now on. Those that are new to the list, and those no longer on it, are drawn afresh. */
    static void update(ClientLevel level, LongOpenHashSet now) {
        LongOpenHashSet before = hidden;
        if (before.equals(now)) return;
        hidden = now;
        hiddenBlocks = now.size();
        if (level == null) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (LongOpenHashSet[] pair : new LongOpenHashSet[][] { { now, before }, { before, now } }) {
            for (LongIterator it = pair[0].iterator(); it.hasNext(); ) {
                long at = it.nextLong();
                if (pair[1].contains(at)) continue;
                pos.set(at);
                BlockState state = level.getBlockState(pos);
                level.sendBlockUpdated(pos, state, state, 0);
            }
        }
    }

    // ------------------------------------------------------------------ blocks

    static void models(ModelEvent.ModifyBakingResult event) {
        try {
            Map<BlockState, BlockStateModel> models = event.getBakingResult().blockStateModels();
            int wrapped = 0;
            for (Block block : List.of(Blocks.FIRE, Blocks.SOUL_FIRE)) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    BlockStateModel model = models.get(state);
                    if (model != null) { models.put(state, new Hiding(model, null)); wrapped++; }
                }
            }
            for (Block block : List.of(Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE)) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    if (!state.getValue(CampfireBlock.LIT)) continue;
                    BlockStateModel lit = models.get(state), out = models.get(state.setValue(CampfireBlock.LIT, false));
                    if (lit != null && out != null) { models.put(state, new Hiding(lit, out)); wrapped++; }
                }
            }
            PhysicsBruhh.LOGGER.info("Gas: {} models of fire and campfires can leave their flames to the gas", wrapped);
        } catch (Throwable t) {
            PhysicsBruhh.LOGGER.warn("Gas: the game's own flames cannot be left out where the gas is drawn ({})", t.toString());
        }
    }

    /** A block's model that, where the gas draws the fire, shows another model instead (a campfire that is out), or nothing. */
    private record Hiding(BlockStateModel shown, BlockStateModel instead) implements BlockStateModel {
        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> parts) { shown.collectParts(random, parts); }

        @Override
        public Material.Baked particleMaterial() { return shown.particleMaterial(); }

        @Override
        public int materialFlags() { return shown.materialFlags(); }

        @Override
        public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
            if (!GasHide.hides(pos)) shown.collectParts(level, pos, state, random, parts);
            else if (instead != null) instead.collectParts(level, pos, state, random, parts);
        }

        /** No key: what this model shows depends on more than the block. */
        @Override
        public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) { return null; }

        @Override
        public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) { return shown.particleMaterial(level, pos, state); }

        @Override
        public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) { return shown.materialFlags(level, pos, state); }
    }

    // ------------------------------------------------------------------ particles

    /** The puffs the gas takes the place of: campfire smoke, the smoke of a block of fire, an explosion. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void particles(RegisterParticleProvidersEvent event) {
        try {
            ParticleResources resources = null;
            for (Field field : RegisterParticleProvidersEvent.class.getDeclaredFields()) {
                if (field.getType() != ParticleResources.class) continue;
                field.setAccessible(true);
                resources = (ParticleResources) field.get(event);
            }
            if (resources == null) throw new IllegalStateException("the event does not hold the game's particles");
            Map<Identifier, ParticleProvider<?>> providers = resources.getProviders();
            int wrapped = 0;
            for (ParticleType<?> type : List.of(ParticleTypes.CAMPFIRE_COSY_SMOKE, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, ParticleTypes.LARGE_SMOKE, ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER)) {
                Identifier id = BuiltInRegistries.PARTICLE_TYPE.getKey(type);
                final ParticleProvider usual = providers.get(id);
                if (usual == null) continue;
                providers.put(id, (ParticleProvider) (options, level, x, y, z, xa, ya, za, random) -> {
                    if (GasClient.draws(x, y, z)) { particlesLeftOut++; return null; }
                    return usual.createParticle(options, level, x, y, z, xa, ya, za, random);
                });
                wrapped++;
            }
            PhysicsBruhh.LOGGER.info("Gas: {} kinds of puff are left to the gas where it is drawn", wrapped);
        } catch (Throwable t) {
            PhysicsBruhh.LOGGER.warn("Gas: the game's own puffs of smoke cannot be left out where the gas is drawn ({})", t.toString());
        }
    }

    /** The flames across the screen of a burning player: the gas around the player is those flames. */
    static void screen(ExtractBlockScreenEffectEvent event) {
        if (event.getOverlayType() == ExtractBlockScreenEffectEvent.OverlayType.FIRE && GasClient.showing()) event.setCanceled(true);
    }
}
