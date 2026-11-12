package physicsbruhh.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.PrepareRenderBuffersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import physicsbruhh.PhysicsBruhh;
import physicsbruhh.sim.LavaMesher;

/**
 * Lava as the mod draws it: every block of lava the game would draw, wherever it is, is drawn by
 * the mod instead, as one smooth surface with a shader of its own ({@code shaders/core/lava.*}).
 * The blocks of lava stay exactly what they are to the game; only the picture is the mod's.
 *
 * <p>How it goes together:
 * <ol>
 *   <li>the threads that build the chunks hand over, for every section they build, the surface of
 *       the lava in it ({@link LavaHide}, {@link LavaMesher});</li>
 *   <li>before a frame's drawing starts, what has come in is taken up here: where a section had a
 *       surface before, every point of the new one starts from the height the old one had there
 *       and travels to its new height (lava that is new to a block comes in from the side it runs
 *       from), and the result goes to the graphics card, a buffer a section;</li>
 *   <li>after the game has drawn its solid blocks, in the same pass, the sections in view are drawn.</li>
 * </ol>
 * With a shader pack on, on a machine where the shader does not work, or after {@code /lavalook
 * off}, the game's own lava is back (the sections with lava in them are built afresh).
 */
final class LavaClient {
    private LavaClient() {}

    /** The player's choice ({@code /lavalook on|off}). */
    static volatile boolean chosen = true;
    /** Set when the mod's lava failed on this machine; it then stays off until the game is restarted. */
    static volatile boolean broken;
    /** The mod's lava is what is drawn right now (asked by the threads that build chunks). */
    static volatile boolean active;

    /** A section the game has built that has no lava to draw. */
    static final LavaMesher.Mesh NONE = new LavaMesher.Mesh();
    /** Surfaces from the threads that build chunks, by section, until the next frame takes them up. */
    private static final Map<Long, LavaMesher.Mesh> ARRIVED = new ConcurrentHashMap<>();

    /** Faces in one buffer at most (a section with more gets several): keeps a buffer under 65536 texels, which every graphics card allows. */
    private static final int PART = 8000;
    /** Seconds a height takes to get where it is going, and the longest lava new to a block takes to get across it. */
    private static final float RISE = 0.4F, ACROSS = 0.45F;
    /** Calm faces in one buffer at most: each is cut into 36 as it is drawn. */
    private static final int CALM_PART = 2000, CUT = 36;
    private static final int FRAME_BYTES = 128;

    static final BindGroupLayout FRAME = BindGroupLayout.builder().withUniform("LavaFrame", UniformType.UNIFORM_BUFFER).build();
    private static final BindGroupLayout MESH = BindGroupLayout.builder().withUniform("LavaMesh", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT).build();
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "core/lava");
    /** Drawn inside the game's own pass for solid blocks: the same picture and depth, tested and written like a solid block's. */
    private static RenderPipeline.Builder surface(String name) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "pipeline/" + name))
                .withVertexShader(SHADER)
                .withFragmentShader(SHADER)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
                .withBindGroupLayout(FRAME)
                .withBindGroupLayout(MESH)
                .withBindGroupLayout(LavaWaves.SEEN)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(ColorTargetState.DEFAULT);
    }

    private static final RenderPipeline PIPELINE = surface("lava").build();
    /** The tops of calm blocks, a face a block, each cut into six by six by the vertex shader: for the waves to move. */
    private static final RenderPipeline PIPELINE_CUT = surface("lava_cut").withShaderDefine("LAVA_CUT").build();

    /** The same pass and the same pixels for the domes and drops of bubbles; their corners are made by the vertex shader. */
    private static final RenderPipeline BUBBLES = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "pipeline/lava_bubbles"))
            .withVertexShader(Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "core/lava_bubbles"))
            .withFragmentShader(SHADER)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withBindGroupLayout(FRAME)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("LavaBubbles", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT).build())
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    static void register(RegisterRenderPipelinesEvent event) {
        // optional: if one does not compile on some driver the game still starts, with its own lava
        event.registerOptionalPipeline(PIPELINE);
        event.registerOptionalPipeline(PIPELINE_CUT);
        event.registerOptionalPipeline(BUBBLES);
        LavaWaves.register(event);
    }

    private static boolean available() {
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(PIPELINE);
        return compiled != null && !compiled.isClosed();
    }

    // ------------------------------------------------------------------ what is kept of a section

    private static final class Section {
        final int sx, sy, sz;
        GpuBuffer[] parts = new GpuBuffer[0];
        int[] partQuads = new int[0];
        /** The tops of its calm blocks, a face a block (LavaMesher): drawn as they are far away, cut finer where the waves are. */
        GpuBuffer[] calmParts = new GpuBuffer[0];
        int[] calmQuads = new int[0];
        int quads;
        float minY, maxY;
        /** For each of its columns: the height its topmost lava rests at, from its floor; NaN where there is none. */
        float[] rests = new float[0];
        /** The points of the top: which (LavaMesher's numbers), and for each where it comes from, where it goes, and when it set off. */
        final Int2IntOpenHashMap index = new Int2IntOpenHashMap();
        float[] from = new float[0], to = new float[0], start = new float[0];

        Section(long key) {
            sx = SectionPos.x(key); sy = SectionPos.y(key); sz = SectionPos.z(key);
            index.defaultReturnValue(-1);
        }

        /** How high a point of the top stands right now (as the shader has it), or NaN if the section has no such point. */
        float height(int key, float now) {
            int i = index.get(key);
            return i < 0 ? Float.NaN : eased(from[i], to[i], start[i], now);
        }

        /**
         * The same for a point the section did not have as such: if the block it lies in had a top, that top was a flat
         * sheet between its four corners (a calm block is cut finer when its neighbour starts to bend, and nothing moved).
         */
        float between(int key, float now) {
            final int across = LavaMesher.ACROSS, fine = LavaMesher.FINE;
            int ix = key % across, iz = key / across % across, layer = key / (across * across) * across * across;
            for (int tryX = 0; tryX < 2; tryX++) {
                int bx = ix / fine - tryX;
                if (bx < 0 || bx > 15 || (tryX == 1 && ix % fine != 0)) continue;
                for (int tryZ = 0; tryZ < 2; tryZ++) {
                    int bz = iz / fine - tryZ;
                    if (bz < 0 || bz > 15 || (tryZ == 1 && iz % fine != 0)) continue;
                    int corner = bz * fine * across + bx * fine + layer;
                    float h00 = height(corner, now), h10 = height(corner + fine, now), h01 = height(corner + fine * across, now), h11 = height(corner + fine * across + fine, now);
                    if (h00 != h00 || h10 != h10 || h01 != h01 || h11 != h11) continue;
                    float u = LavaMesher.LINES[ix - bx * fine], v = LavaMesher.LINES[iz - bz * fine];
                    return (h00 * (1.0F - u) + h10 * u) * (1.0F - v) + (h01 * (1.0F - u) + h11 * u) * v;
                }
            }
            return Float.NaN;
        }

        void close() {
            for (GpuBuffer part : parts) part.close();
            for (GpuBuffer part : calmParts) part.close();
            parts = new GpuBuffer[0];
            calmParts = new GpuBuffer[0];
            quads = 0;
        }
    }

    private static float eased(float from, float to, float start, float now) {
        float t = Math.max(0.0F, Math.min(1.0F, (now - start) / RISE));
        return from + (to - from) * t * t * (3.0F - 2.0F * t);
    }

    private static final Long2ObjectOpenHashMap<Section> SECTIONS = new Long2ObjectOpenHashMap<>();
    /** Sections the game has built and found no lava in: if lava turns up there, it has come in, and is shown coming in. */
    private static final LongOpenHashSet SEEN_EMPTY = new LongOpenHashSet();
    private static ClientLevel world;
    private static float spread = 1.5F;
    private static final long EPOCH = System.nanoTime();
    private static MappableRingBuffer frameBuffer;
    private static boolean prepared;
    private static ByteBuffer scratch = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());
    private static int ticks;

    /** For {@code /lavalook info} and the smoke test. */
    static volatile int sections, quads, drawnSections, drawnQuads, cutQuads, frames, uploads;
    static volatile float prepareMillis;

    /** Seconds since the game started: what heights on the move and bubbles are timed by (the shaders are told the same). */
    static float clock() {
        return (float) ((System.nanoTime() - EPOCH) / 1.0e9);
    }

    // ------------------------------------------------------------------ from the threads that build chunks

    static void arrived(long section, LavaMesher.Mesh mesh) {
        ARRIVED.put(section, mesh);
    }

    static void failed(String what, Throwable t) {
        if (!broken) PhysicsBruhh.LOGGER.error("Lava: {} failed on this machine; the mod's lava is switched off and the game's own is back", what, t);
        broken = true;
    }

    // ------------------------------------------------------------------ every tick

    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != world) {
            clear();
            world = level;
        }
        boolean want = chosen && !broken && !Packs.active && LavaHide.hooked && level != null && available();
        if (want != active) {
            active = want;
            if (!want) clear();
            if (level != null) {
                int built = afresh(mc, level);
                PhysicsBruhh.LOGGER.info(want ? "Lava: drawn by the mod ({} sections with lava are built afresh)" : "Lava: drawn by the game again ({} sections with lava are built afresh)", built);
            }
        }
        if (level == null || !active) return;
        spread = Math.max(1, Fluids.LAVA.getTickDelay(level)) / 20.0F;
        if (++ticks % 40 == 0) forget(mc, level);
        if (!SECTIONS.isEmpty() && !mc.isPaused()) {
            LavaBubbles.tick(mc, level, clock());
            LavaWaves.tick(mc, level);
        }
    }

    /** Every section in reach that has lava in it is built afresh by the game: that is when its lava changes hands. */
    private static int afresh(Minecraft mc, ClientLevel level) {
        if (mc.player == null) return 0;
        int reach = mc.options.renderDistance().get() + 2;
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4, built = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = cz - reach; z <= cz + reach; z++) {
            for (int x = cx - reach; x <= cx + reach; x++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] parts = chunk.getSections();
                for (int i = 0; i < parts.length; i++) {
                    LevelChunkSection part = parts[i];
                    if (part == null || part.hasOnlyAir() || !part.maybeHas(state -> state.getFluidState().getType().isSame(Fluids.LAVA))) continue;
                    pos.set((x << 4) + 8, (chunk.getSectionYFromSectionIndex(i) << 4) + 8, (z << 4) + 8);
                    BlockState state = level.getBlockState(pos);
                    level.sendBlockUpdated(pos, state, state, 0);
                    built++;
                }
            }
        }
        return built;
    }

    /** Sections whose chunk is gone are let go of. */
    private static void forget(Minecraft mc, ClientLevel level) {
        for (Iterator<Section> it = SECTIONS.values().iterator(); it.hasNext(); ) {
            Section section = it.next();
            if (level.getChunkSource().hasChunk(section.sx, section.sz)) continue;
            section.close();
            it.remove();
        }
        for (LongIterator it = SEEN_EMPTY.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if (!level.getChunkSource().hasChunk(SectionPos.x(key), SectionPos.z(key))) it.remove();
        }
        count();
    }

    private static void count() {
        int all = 0;
        for (Section section : SECTIONS.values()) all += section.quads;
        sections = SECTIONS.size();
        quads = all;
    }

    private static void clear() {
        ARRIVED.clear();
        LavaBubbles.clear();
        LavaWaves.clear();
        for (Section section : SECTIONS.values()) section.close();
        SECTIONS.clear();
        SEEN_EMPTY.clear();
        prepared = false;
        sections = 0; quads = 0; drawnSections = 0; drawnQuads = 0;
    }

    static void reset() {
        clear();
        world = null;
        active = false;
    }

    // ------------------------------------------------------------------ every frame, before the drawing starts

    static void prepare(PrepareRenderBuffersEvent event) {
        prepared = false;
        if (!active) return;
        try {
            long began = System.nanoTime();
            float now = clock();
            if (!ARRIVED.isEmpty()) {
                for (Iterator<Map.Entry<Long, LavaMesher.Mesh>> it = ARRIVED.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<Long, LavaMesher.Mesh> entry = it.next();
                    LavaMesher.Mesh mesh = entry.getValue();
                    // (if a newer one has come in for the section in the meantime, that one stays for the next frame)
                    if (!ARRIVED.remove(entry.getKey(), mesh)) continue;
                    take(entry.getKey(), mesh, now);
                    if (System.nanoTime() - began > 4_000_000L) break;
                }
                count();
            }
            if (SECTIONS.isEmpty()) return;
            BlockPos eye = event.getLevelRenderState().cameraRenderState.blockPos;
            LavaBubbles.write(eye.getX() >> 4, eye.getY() >> 4, eye.getZ() >> 4);
            // (asked for here, so that it is not made larger in the middle of the pass)
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(most() * 6);
            // the waves: where their square is, where the lava in it rests, and a step of them
            LavaWaves.place(eye);
            if (LavaWaves.beginMap(eye)) {
                for (Section section : SECTIONS.values()) {
                    if (!LavaWaves.covers(section.sx, section.sy, section.sz)) continue;
                    for (int i = 0; i < section.rests.length; i++) {
                        float rest = section.rests[i];
                        if (rest == rest) LavaWaves.rest((section.sx << 4) + (i & 15), (section.sy << 4) + rest, (section.sz << 4) + (i >> 4));
                    }
                }
                LavaWaves.endMap();
            }
            writeFrame(event, now, eye);
            LavaWaves.step(frameBuffer.currentBuffer());
            prepared = true;
            prepareMillis += ((System.nanoTime() - began) / 1.0e6F - prepareMillis) * 0.1F;
        } catch (Throwable t) {
            failed("getting the lava ready to draw", t);
        }
    }

    /** The most faces any one draw is of (the indices for that many have to be there). */
    private static int most() {
        int most = LavaBubbles.MOST * LavaBubbles.FACES;
        for (Section section : SECTIONS.values()) {
            for (int n : section.partQuads) most = Math.max(most, n);
            for (int n : section.calmQuads) most = Math.max(most, n * CUT);
        }
        return most;
    }

    /** A section's new surface: its points start from where the old surface had them. */
    private static void take(long key, LavaMesher.Mesh mesh, float now) {
        Section old = SECTIONS.get(key);
        if (mesh.quads == 0) {
            if (old != null) { old.close(); SECTIONS.remove(key); LavaWaves.changed(); }
            SEEN_EMPTY.add(key);
            return;
        }
        boolean known = old != null || SEEN_EMPTY.remove(key);
        Section section = new Section(key);
        final int corners = mesh.quads * 4, floats = LavaMesher.VERTEX_FLOATS;
        float[] data = mesh.data;
        // the points of the top, each once
        int points = 0;
        float[] to = new float[64], late = new float[64];
        int[] which = new int[64];
        for (int v = 0; v < corners; v++) {
            int point = mesh.key[v];
            if (point < 0) continue;
            int i = section.index.get(point);
            if (i < 0) {
                if (points == to.length) { to = java.util.Arrays.copyOf(to, points * 2); late = java.util.Arrays.copyOf(late, points * 2); which = java.util.Arrays.copyOf(which, points * 2); }
                i = points++;
                section.index.put(point, i);
                which[i] = point;
                to[i] = data[v * floats + 1];
                late[i] = mesh.delay[v];
            } else if (mesh.delay[v] < late[i]) {
                late[i] = mesh.delay[v];
            }
        }
        float[] from = new float[points], start = new float[points];
        final int layerPoints = LavaMesher.ACROSS * LavaMesher.ACROSS;
        for (int i = 0; i < points; i++) {
            float was = Float.NaN;
            if (old != null) {
                was = old.height(which[i], now);
                if (was != was) was = old.between(which[i], now);
            }
            if (was == was) {
                from[i] = was;
                start[i] = Math.abs(was - to[i]) < 0.003F ? -1.0e6F : now;
            } else if (known) {
                // lava where there was none: up from the floor, later the further from where it comes in
                from[i] = which[i] / layerPoints + 0.004F;
                start[i] = to[i] - from[i] < 0.01F ? -1.0e6F : now + late[i] * Math.min(ACROSS, 0.5F * spread);
            } else {
                from[i] = to[i];
                start[i] = -1.0e6F;
            }
        }
        section.from = from;
        section.to = java.util.Arrays.copyOf(to, points);
        section.start = start;
        for (int v = 0; v < corners; v++) {
            int point = mesh.key[v];
            if (point < 0) continue;
            int i = section.index.get(point), o = v * floats;
            data[o + 1] = to[i];
            data[o + 4] = from[i];
            data[o + 5] = start[i];
        }

        // to the graphics card: a texel that says which section this is, then two texels a corner; the calm faces apart
        section.parts = send(section, data, 0, mesh.calmFrom, PART);
        section.partQuads = sizes(mesh.calmFrom, PART);
        section.calmParts = send(section, data, mesh.calmFrom, mesh.quads - mesh.calmFrom, CALM_PART);
        section.calmQuads = sizes(mesh.quads - mesh.calmFrom, CALM_PART);
        section.rests = mesh.rests;
        section.quads = mesh.quads;
        section.minY = mesh.minY;
        section.maxY = mesh.maxY;
        if (old != null) old.close();
        SECTIONS.put(key, section);
        LavaWaves.changed();
    }

    private static int[] sizes(int faces, int most) {
        int[] sizes = new int[(faces + most - 1) / most];
        for (int p = 0; p < sizes.length; p++) sizes[p] = Math.min(most, faces - p * most);
        return sizes;
    }

    private static GpuBuffer[] send(Section section, float[] data, int from, int faces, int most) {
        final int floats = LavaMesher.VERTEX_FLOATS;
        GpuBuffer[] parts = new GpuBuffer[(faces + most - 1) / most];
        for (int p = 0; p < parts.length; p++) {
            int first = from + p * most, n = Math.min(most, faces - p * most);
            int bytes = (1 + n * 8) * 16;
            if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            scratch.clear();
            FloatBuffer out = scratch.asFloatBuffer();
            out.put(section.sx).put(section.sy).put(section.sz).put(0.0F);
            out.put(data, first * 4 * floats, n * 4 * floats);
            scratch.limit(bytes);
            parts[p] = RenderSystem.getDevice().createBuffer(() -> "Lava: the surface in a section", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, scratch);
            uploads++;
        }
        return parts;
    }

    /** What the shaders are told about the frame (the {@code LavaFrame} block, see {@code include/lava_frame.glsl}). */
    private static void writeFrame(PrepareRenderBuffersEvent event, float now, BlockPos eye) {
        if (frameBuffer == null) frameBuffer = new MappableRingBuffer(() -> "Lava frame", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, FRAME_BYTES);
        frameBuffer.rotate();
        try (GpuBufferSlice.MappedView view = frameBuffer.currentBuffer().map(false, true)) {
            ByteBuffer data = view.data().order(ByteOrder.nativeOrder());
            new Matrix4f(event.getLevelRenderState().cameraRenderState.viewRotationMatrix).get(0, data);
            FloatBuffer out = data.asFloatBuffer();
            out.put(16, now).put(17, (float) (System.nanoTime() / 1.0e9 % 4096.0)).put(18, RISE).put(19, 1.0F);
            out.put(20, 0.35F).put(21, 1.0F).put(22, 0.0F).put(23, 0.0F);
            LavaWaves.describe(out, 24, eye);
        }
    }

    // ------------------------------------------------------------------ every frame, after the solid blocks

    static void draw(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        if (!prepared || !active) return;
        prepared = false;
        try {
            CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(PIPELINE);
            if (compiled == null || compiled.isClosed()) throw new IllegalStateException("shader pipeline " + PIPELINE.getLocation() + " is not available");
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            int most = most();
            if (!indices.hasStorage(most * 6)) return;
            GpuBuffer indexBuffer = indices.getBuffer(most * 6);
            Frustum frustum = event.getLevelRenderState().cameraRenderState.cullFrustum;
            RenderPass pass = event.getRenderPass();
            var lightmap = Minecraft.getInstance().gameRenderer.lightmap();
            var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            CompiledRenderPipeline cutting = RenderSystem.getCompiledPipelineNullable(PIPELINE_CUT);
            boolean cuts = cutting != null && !cutting.isClosed();
            int shown = 0, faces = 0, cutFaces = 0;
            // first everything that is drawn as it is; then, where the waves are, the calm blocks cut finer
            for (int round = 0; round < (cuts ? 2 : 1); round++) {
                pass.setPipeline(round == 0 ? compiled : cutting);
                pass.setUniform("LavaFrame", frameBuffer.currentBuffer());
                pass.setUniform("Sampler2", lightmap, linear);
                pass.setUniform("LavaWavesNow", LavaWaves.now(), linear);
                pass.setUniform("LavaWavesMap", LavaWaves.map(), nearest);
                pass.setVertexBuffer(0, null);
                pass.setIndexBuffer(indexBuffer, indices.type());
                for (Section section : SECTIONS.values()) {
                    boolean moves = cuts && LavaWaves.moves(section.sx, section.sy, section.sz);
                    if (round == 1 && (!moves || section.calmParts.length == 0)) continue;
                    double x = section.sx << 4, y = section.sy << 4, z = section.sz << 4;
                    if (frustum != null && !frustum.isVisible(new AABB(x - 0.1, y + section.minY - 0.7, z - 0.1, x + 16.1, y + section.maxY + 0.7, z + 16.1))) continue;
                    if (round == 0) {
                        for (int p = 0; p < section.parts.length; p++) {
                            pass.setUniform("LavaMesh", section.parts[p]);
                            pass.drawIndexed(section.partQuads[p] * 6, 1, 0, 0, 0);
                        }
                        shown++;
                        faces += section.quads;
                    }
                    if ((round == 1) == moves) {
                        for (int p = 0; p < section.calmParts.length; p++) {
                            pass.setUniform("LavaMesh", section.calmParts[p]);
                            pass.drawIndexed(section.calmQuads[p] * (moves ? CUT : 1) * 6, 1, 0, 0, 0);
                            if (moves) cutFaces += section.calmQuads[p];
                        }
                    }
                }
            }
            cutQuads = cutFaces;
            int bubbles = LavaBubbles.written();
            CompiledRenderPipeline bubbly = bubbles > 0 ? RenderSystem.getCompiledPipelineNullable(BUBBLES) : null;
            if (bubbly != null && !bubbly.isClosed()) {
                pass.setPipeline(bubbly);
                pass.setUniform("LavaFrame", frameBuffer.currentBuffer());
                pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                pass.setUniform("LavaBubbles", LavaBubbles.buffer());
                pass.setVertexBuffer(0, null);
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(bubbles * LavaBubbles.FACES * 6, 1, 0, 0, 0);
            }
            drawnSections = shown;
            drawnQuads = faces;
            if (shown > 0) frames++;
        } catch (Throwable t) {
            failed("drawing the lava", t);
        }
    }

    // ------------------------------------------------------------------ the player's choice

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("physicsbruhh-lava.properties");
    }

    static void load() {
        String set = System.getenv("PHYSICSBRUHH_LAVA") != null ? System.getenv("PHYSICSBRUHH_LAVA") : System.getProperty("physicsbruhh.lava");
        try {
            Path file = file();
            if (set == null && Files.exists(file)) {
                Properties properties = new Properties();
                try (var in = Files.newInputStream(file)) { properties.load(in); }
                set = properties.getProperty("lava");
            }
        } catch (Exception e) {
            PhysicsBruhh.LOGGER.warn("Could not read the lava setting: {}", e.toString());
        }
        if (set != null) chosen = !set.trim().toLowerCase(Locale.ROOT).matches("off|false|0|no");
    }

    private static int choose(boolean on) {
        chosen = on;
        try {
            Properties properties = new Properties();
            properties.setProperty("lava", on ? "on" : "off");
            try (var out = Files.newOutputStream(file())) { properties.store(out, "Physics Bruhh, lava, this client only. lava = on | off"); }
        } catch (Exception e) {
            PhysicsBruhh.LOGGER.warn("Could not save the lava setting: {}", e.toString());
        }
        return 1;
    }

    /** Why the mod's lava is not what is drawn although the player wants it, for the chat; null if it is. */
    private static Component whyNot() {
        if (broken) return Component.translatableWithFallback("commands.physicsbruhh.lava.broken", "The mod's lava did not work on this machine (see the log); the game's own lava is drawn");
        if (!LavaHide.hooked) return Component.translatableWithFallback("commands.physicsbruhh.lava.unhooked", "The mod's lava could not take the place of the game's in this version of the game (see the log); the game's own lava is drawn");
        if (Packs.active) return Component.translatableWithFallback("commands.physicsbruhh.lava.pack", "A shader pack is on: it draws the lava its own way, the mod's lava waits until it is off");
        if (!available()) return Component.translatableWithFallback("commands.physicsbruhh.lava.broken", "The mod's lava did not work on this machine (see the log); the game's own lava is drawn");
        return null;
    }

    static void registerCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lavalook")
                .then(Commands.literal("on").executes(context -> {
                    choose(true);
                    Component why = whyNot();
                    context.getSource().sendSuccess(() -> why != null ? why : Component.translatableWithFallback("commands.physicsbruhh.lava.on", "Lava is drawn by the mod: one smooth surface that glows by how hot it is, with a crust that is carried along and torn where it runs"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(context -> {
                    choose(false);
                    context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.physicsbruhh.lava.off", "Lava is left as the game draws it on this client"), false);
                    return 1;
                }))
                .then(Commands.literal("info").executes(context -> {
                    Component why = chosen ? whyNot() : Component.translatableWithFallback("commands.physicsbruhh.lava.off", "Lava is left as the game draws it on this client");
                    context.getSource().sendSuccess(() -> why != null ? why : Component.translatableWithFallback("commands.physicsbruhh.lava.info",
                            "Lava: drawn by the mod in %s sections of the world (%s faces), %s of them in view (%s faces); taking up what changed and a step of the waves cost %s ms a frame. Waves: %s (%s columns of lava around you, %s calm blocks cut finer for them); %s bubbles right now",
                            sections, quads, drawnSections, drawnQuads, String.format(Locale.ROOT, "%.2f", prepareMillis),
                            LavaWaves.off ? Component.translatableWithFallback("commands.physicsbruhh.lava.waves.off", "off, they did not work on this machine (see the log)") : Component.translatableWithFallback("commands.physicsbruhh.lava.waves.on", "on"),
                            LavaWaves.lavaColumns, cutQuads, LavaBubbles.alive), false);
                    return 1;
                })));
    }
}
