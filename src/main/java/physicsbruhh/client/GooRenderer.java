package physicsbruhh.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.NeoForgeRenderTypes;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import physicsbruhh.PhysicsBruhh;
import physicsbruhh.sim.GooSim;

/**
 * Draws the goo surface. The mesh comes from the simulation thread as quads (position + normal,
 * the normal's length carrying how deep the goo is behind that point).
 *
 * <p>Normally the goo's own shader does the work (see {@link GooPipelines}): per-pixel gloss, the
 * sky mirrored in the surface, colour by depth; this class then only hands over positions, normals,
 * depth, material and the light level. The game rule {@code clear_goo} picks between the block's
 * pixel texture and a clear liquid as the body colour.
 *
 * <p>While a shader pack is on (see {@link Packs}) none of the mod's shaders are used: the quads go
 * out as ordinary translucent geometry with the light at their place and their normals, and the
 * pack lights them, mirrors in them and throws their shadow as it does with everything else.
 *
 * <p>{@code /goolook plain} (or {@code -Dphysicsbruhh.shader=false}) switches to the fallback that
 * needs no shader of its own: the same two looks, shaded per vertex on the CPU and drawn through
 * one of the game's stock pipelines. It is there in case the shader does not work on some machine.
 */
public final class GooRenderer {
    private static final Identifier[] TEXTURES = {
            Identifier.withDefaultNamespace("textures/block/honey_block_side.png"),
            Identifier.withDefaultNamespace("textures/block/slime_block.png")
    };
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath(PhysicsBruhh.MODID, "textures/white.png");
    private static final int[] ALPHA = { 0xD8, 0xC8 };
    private static final float[][] local = { new float[0], new float[0] };
    private static final int[] quads = { 0, 0 };
    private static int seenVersion = -1;
    /** Draw callbacks that actually ran, and quads emitted by them (smoke test and debugging). */
    static volatile int drawCalls, drawnQuads;
    /** Draw callbacks that handed the goo to a shader pack (smoke test). */
    static volatile int packDraws;
    /** Use the goo shader (true) or the stock-pipeline fallback (false). */
    static volatile boolean shader = !"false".equalsIgnoreCase(System.getProperty("physicsbruhh.shader"));

    // light the clear look is shaded with: high, a little to the south-east
    private static final float LX = 0.42F, LY = 0.80F, LZ = 0.43F;
    // how much red / green / blue light gets through goo of a given thickness (Beer-Lambert), tabulated per material
    private static final int ABSORB_STEPS = 256;
    private static final float ABSORB_MAX = 2.6F;
    private static final double[][] ABSORB = { { 0.5, 2.5, 10.0 }, { 3.2, 0.45, 4.5 } };
    private static final float[][][] PASS = new float[2][3][ABSORB_STEPS + 1];
    // colour of a thin film and of a thick mass
    private static final float[][] THIN = { { 1.00F, 0.74F, 0.20F }, { 0.66F, 0.96F, 0.52F } };
    private static final float[][] THICK = { { 0.86F, 0.38F, 0.02F }, { 0.20F, 0.60F, 0.16F } };

    static {
        for (int mat = 0; mat < 2; mat++) for (int ch = 0; ch < 3; ch++) for (int i = 0; i <= ABSORB_STEPS; i++) {
            PASS[mat][ch][i] = (float) Math.exp(-ABSORB[mat][ch] * i * (double) ABSORB_MAX / ABSORB_STEPS);
        }
    }

    private GooRenderer() {}

    static void submit(SubmitCustomGeometryEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        // clear goo is normally drawn as a liquid, after the rest of the world (GooFluid); there is no surface mesh then
        if (GooClient.clearLook && GooFluid.inUse()) return;
        // take a private copy of the latest mesh; the draw callbacks below may run later in the frame
        synchronized (GooClient.MESH_LOCK) {
            if (GooClient.meshVersion != seenVersion) {
                seenVersion = GooClient.meshVersion;
                for (int mat = 0; mat < 2; mat++) {
                    int floats = GooClient.meshQuads[mat] * GooSim.FLOATS_PER_QUAD;
                    if (local[mat].length < floats) local[mat] = new float[floats + floats / 2];
                    System.arraycopy(GooClient.meshData[mat], 0, local[mat], 0, floats);
                    quads[mat] = GooClient.meshQuads[mat];
                }
            }
        }
        if (quads[0] == 0 && quads[1] == 0) return;
        BlockPos origin = GooClient.origin;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        final float camX = (float) (camera.x - origin.getX()), camY = (float) (camera.y - origin.getY()), camZ = (float) (camera.z - origin.getZ());
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        try {
            stack.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
            for (int mat = 0; mat < 2; mat++) {
                final int count = quads[mat];
                if (count == 0) continue;
                final float[] data = local[mat];
                final int alpha = ALPHA[mat];
                final boolean clear = GooClient.clearLook;
                final int material = mat;
                if (Packs.active) {
                    // a shader pack is drawing the world: plain translucent geometry of the game, which the pack lights and shadows itself
                    event.getSubmitNodeCollector().submitCustomGeometry(stack, RenderTypes.entityTranslucent(clear ? WHITE : TEXTURES[mat]), (pose, consumer) -> {
                        drawCalls++; drawnQuads += count; packDraws++;
                        packQuads(pose, consumer, level, origin, data, count, material, clear, camX, camY, camZ);
                    });
                    continue;
                }
                if (shader) {
                    event.getSubmitNodeCollector().submitCustomGeometry(stack, GooPipelines.type(TEXTURES[mat]), (pose, consumer) -> {
                        drawCalls++; drawnQuads += count;
                        shaderQuads(pose, consumer, level, origin, data, count, material, clear);
                    });
                    continue;
                }
                RenderType type = NeoForgeRenderTypes.getEntityUnlitTranslucent(clear ? WHITE : TEXTURES[mat]);
                event.getSubmitNodeCollector().submitCustomGeometry(stack, type, (pose, consumer) -> {
                    drawCalls++; drawnQuads += count;
                    Long2FloatOpenHashMap lightCache = new Long2FloatOpenHashMap();
                    lightCache.defaultReturnValue(-1.0F);
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    for (int q = 0; q < count; q++) {
                        int o = q * GooSim.FLOATS_PER_QUAD;
                        // light of the block under the quad's first corner, cached per block
                        pos.set(origin.getX() + (int) Math.floor(data[o]), origin.getY() + (int) Math.floor(data[o + 1] + 0.05F), origin.getZ() + (int) Math.floor(data[o + 2]));
                        float light = lightCache.get(pos.asLong());
                        if (light < 0.0F) { light = 0.22F + 0.78F * level.getMaxLocalRawBrightness(pos) / 15.0F; lightCache.put(pos.asLong(), light); }
                        if (clear) clearQuad(pose, consumer, data, o, light, camX, camY, camZ, material);
                        else blockyQuad(pose, consumer, data, o, light, alpha);
                    }
                });
            }
        } finally {
            stack.popPose();
        }
    }

    /**
     * Hands the quads to the goo shader. Colour is not a colour here but data for it:
     * red = material, green = look, alpha = depth of goo behind the vertex (in 1/255 of 2.6 blocks).
     */
    private static void shaderQuads(PoseStack.Pose pose, VertexConsumer consumer, ClientLevel level, BlockPos origin, float[] data, int count, int material, boolean clear) {
        Long2IntOpenHashMap lightCache = new Long2IntOpenHashMap();
        lightCache.defaultReturnValue(-1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int red = material == GooSim.SLIME ? 255 : 0, green = clear ? 255 : 0;
        for (int q = 0; q < count; q++) {
            int o = q * GooSim.FLOATS_PER_QUAD;
            // block light and sky light where the quad is, cached per block
            pos.set(origin.getX() + (int) Math.floor(data[o]), origin.getY() + (int) Math.floor(data[o + 1] + 0.05F), origin.getZ() + (int) Math.floor(data[o + 2]));
            int light = lightCache.get(pos.asLong());
            if (light < 0) {
                light = level.getBrightness(LightLayer.BLOCK, pos) << 4 | level.getBrightness(LightLayer.SKY, pos) << 20;
                lightCache.put(pos.asLong(), light);
            }
            for (int v = 0; v < 4; v++) {
                int i = o + v * 6;
                float nx = data[i + 3], ny = data[i + 4], nz = data[i + 5];
                float len = Math.max(1.0e-6F, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
                int depth = Math.max(0, Math.min(255, (int) ((len - 1.0F) * (255.0F / 2.6F) + 0.5F)));
                consumer.addVertex(pose, data[i], data[i + 1], data[i + 2])
                        .setColor(red, green, 0, depth)
                        .setUv(0.0F, 0.0F)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, nx / len, ny / len, nz / len);
            }
        }
    }

    /**
     * Hands the quads to whatever shader pack is on, as translucent geometry like any other in the
     * game: the block's texture (or, for clear goo, a plain colour that deepens with the depth of
     * goo behind it), the light where the quad is, and its normal. What the light does to it is
     * the pack's business.
     */
    private static void packQuads(PoseStack.Pose pose, VertexConsumer consumer, ClientLevel level, BlockPos origin, float[] data, int count, int material, boolean clear, float camX, float camY, float camZ) {
        Long2IntOpenHashMap lightCache = new Long2IntOpenHashMap();
        lightCache.defaultReturnValue(-1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final float[][] pass = PASS[material];
        final float[] thin = THIN[material], thick = THICK[material];
        for (int q = 0; q < count; q++) {
            int o = q * GooSim.FLOATS_PER_QUAD;
            pos.set(origin.getX() + (int) Math.floor(data[o]), origin.getY() + (int) Math.floor(data[o + 1] + 0.05F), origin.getZ() + (int) Math.floor(data[o + 2]));
            int light = lightCache.get(pos.asLong());
            if (light < 0) {
                light = level.getBrightness(LightLayer.BLOCK, pos) << 4 | level.getBrightness(LightLayer.SKY, pos) << 20;
                lightCache.put(pos.asLong(), light);
            }
            float fx = (float) Math.floor(data[o]), fy = (float) Math.floor(data[o + 1]), fz = (float) Math.floor(data[o + 2]);
            float ax = Math.abs(data[o + 3]), ay = Math.abs(data[o + 4]), az = Math.abs(data[o + 5]);
            if (clear) {
                // one clean layer: surfaces facing away are left out (threads have no depth and are flat ribbons, always drawn)
                boolean thread = data[o + 3] * data[o + 3] + data[o + 4] * data[o + 4] + data[o + 5] * data[o + 5] < 1.002F;
                if (!thread) {
                    float cx = 0.0F, cy = 0.0F, cz = 0.0F, sx = 0.0F, sy = 0.0F, sz = 0.0F;
                    for (int v = 0; v < 4; v++) { int i = o + v * 6; cx += data[i]; cy += data[i + 1]; cz += data[i + 2]; sx += data[i + 3]; sy += data[i + 4]; sz += data[i + 5]; }
                    if (sx * (camX - cx * 0.25F) + sy * (camY - cy * 0.25F) + sz * (camZ - cz * 0.25F) < 0.0F) continue;
                }
            }
            for (int v = 0; v < 4; v++) {
                int i = o + v * 6;
                float x = data[i], y = data[i + 1], z = data[i + 2], nx = data[i + 3], ny = data[i + 4], nz = data[i + 5];
                float len = Math.max(1.0e-6F, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
                nx /= len; ny /= len; nz /= len;
                int colour;
                float u, w;
                if (clear) {
                    float th = Math.min(Math.max(0.0F, len - 1.0F), 2.5F) + 0.03F;
                    int ti = (int) (th * (ABSORB_STEPS / ABSORB_MAX));
                    float a = Math.max(0.45F, 1.0F - (0.30F * pass[0][ti] + 0.55F * pass[1][ti] + 0.15F * pass[2][ti]));
                    float k = Math.min(1.0F, Math.max(0.0F, (th - 0.1F) / 1.3F));
                    k = k * k * (3.0F - 2.0F * k);
                    int r = (int) ((thin[0] + (thick[0] - thin[0]) * k) * 255.0F), g = (int) ((thin[1] + (thick[1] - thin[1]) * k) * 255.0F), b = (int) ((thin[2] + (thick[2] - thin[2]) * k) * 255.0F);
                    colour = ((int) (a * 255.0F) << 24) | (r << 16) | (g << 8) | b;
                    u = 0.5F; w = 0.5F;
                } else {
                    colour = (ALPHA[material] << 24) | 0xFFFFFF;
                    if (ay >= ax && ay >= az) { u = x - fx; w = z - fz; } else if (ax >= az) { u = z - fz; w = 1.0F - (y - fy); } else { u = x - fx; w = 1.0F - (y - fy); }
                }
                consumer.addVertex(pose, x, y, z)
                        .setColor(colour)
                        .setUv(u, w)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, nx, ny, nz);
            }
        }
    }

    private static void blockyQuad(PoseStack.Pose pose, VertexConsumer consumer, float[] data, int o, float light, int alpha) {
        float fx = (float) Math.floor(data[o]), fy = (float) Math.floor(data[o + 1]), fz = (float) Math.floor(data[o + 2]);
        float ax = Math.abs(data[o + 3]), ay = Math.abs(data[o + 4]), az = Math.abs(data[o + 5]);
        for (int v = 0; v < 4; v++) {
            int i = o + v * 6;
            float x = data[i], y = data[i + 1], z = data[i + 2], nx = data[i + 3], ny = data[i + 4], nz = data[i + 5];
            float inv = 1.0F / Math.max(1.0e-6F, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
            nx *= inv; ny *= inv; nz *= inv;
            // top faces brightest, sides dimmer along one axis, like vanilla block shading
            float shade = (0.62F + 0.30F * Math.max(0.0F, ny) + 0.10F * Math.abs(nx) - 0.06F * Math.abs(nz)) * light;
            int c = Math.max(0, Math.min(255, (int) (shade * 255.0F)));
            float u, w;
            if (ay >= ax && ay >= az) { u = x - fx; w = z - fz; } else if (ax >= az) { u = z - fz; w = 1.0F - (y - fy); } else { u = x - fx; w = 1.0F - (y - fy); }
            consumer.addVertex(pose, x, y, z)
                    .setColor((alpha << 24) | (c << 16) | (c << 8) | c)
                    .setUv(u, w)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightCoordsUtil.FULL_BRIGHT)
                    .setNormal(pose, nx, ny, nz);
        }
    }

    private static void clearQuad(PoseStack.Pose pose, VertexConsumer consumer, float[] data, int o, float light, float camX, float camY, float camZ, int mat) {
        final float[][] pass = PASS[mat];
        final float[] thin = THIN[mat], thick = THICK[mat];
        // surfaces facing away are skipped, so the liquid is one clean layer; threads (no depth) are flat ribbons and always drawn
        float n0 = data[o + 3] * data[o + 3] + data[o + 4] * data[o + 4] + data[o + 5] * data[o + 5];
        boolean thread = n0 < 1.002F;
        if (!thread) {
            float cx = 0.0F, cy = 0.0F, cz = 0.0F, sx = 0.0F, sy = 0.0F, sz = 0.0F;
            for (int v = 0; v < 4; v++) { int i = o + v * 6; cx += data[i]; cy += data[i + 1]; cz += data[i + 2]; sx += data[i + 3]; sy += data[i + 4]; sz += data[i + 5]; }
            if (sx * (camX - cx * 0.25F) + sy * (camY - cy * 0.25F) + sz * (camZ - cz * 0.25F) < 0.0F) return;
        }
        for (int v = 0; v < 4; v++) {
            int i = o + v * 6;
            float x = data[i], y = data[i + 1], z = data[i + 2], nx = data[i + 3], ny = data[i + 4], nz = data[i + 5];
            float len = Math.max(1.0e-6F, (float) Math.sqrt(nx * nx + ny * ny + nz * nz));
            float depth = Math.max(0.0F, len - 1.0F);
            nx /= len; ny /= len; nz /= len;
            float vx = camX - x, vy = camY - y, vz = camZ - z;
            float vl = 1.0F / Math.max(1.0e-4F, (float) Math.sqrt(vx * vx + vy * vy + vz * vz));
            vx *= vl; vy *= vl; vz *= vl;
            float nv = nx * vx + ny * vy + nz * vz;
            if (nv < 0.0F) { if (thread) { nv = -nv; nx = -nx; ny = -ny; nz = -nz; } else nv = 0.0F; }
            // the path through the goo is longer when you look along the surface
            float th = Math.min(depth / Math.max(nv, 0.35F), 2.5F) + 0.03F;
            int ti = (int) (th * (ABSORB_STEPS / ABSORB_MAX));
            float a = 1.0F - (0.30F * pass[0][ti] + 0.55F * pass[1][ti] + 0.15F * pass[2][ti]);
            if (a < 0.34F) a = 0.34F;
            float k = Math.min(1.0F, Math.max(0.0F, (th - 0.1F) / 1.3F));
            k = k * k * (3.0F - 2.0F * k);
            // pale where thin, deep where thick
            float ndl = Math.max(0.0F, nx * LX + ny * LY + nz * LZ);
            float lit = (0.64F + 0.36F * ndl) * light;
            float r = (thin[0] + (thick[0] - thin[0]) * k) * lit, g = (thin[1] + (thick[1] - thin[1]) * k) * lit, b = (thin[2] + (thick[2] - thin[2]) * k) * lit;
            // the sky mirrors in it at grazing angles
            float e = 1.0F - nv, e2 = e * e;
            float fres = (0.04F + 0.70F * e2 * e2 * e) * 0.85F;
            r += (0.80F * light - r) * fres; g += (0.90F * light - g) * fres; b += (1.00F * light - b) * fres;
            a += (1.0F - a) * fres;
            // highlight: a tight glint and a soft sheen around it
            float hx = LX + vx, hy = LY + vy, hz = LZ + vz;
            float hl = 1.0F / Math.max(1.0e-4F, (float) Math.sqrt(hx * hx + hy * hy + hz * hz));
            float ndh = Math.max(0.0F, (nx * hx + ny * hy + nz * hz) * hl);
            float p2 = ndh * ndh, p4 = p2 * p2, p8 = p4 * p4, p16 = p8 * p8, p32 = p16 * p16, p64 = p32 * p32, p128 = p64 * p64;
            float spec = (p128 * p8 * p4 * 1.1F + p16 * p4 * p2 * 0.08F) * light;
            r += spec; g += spec; b += spec; a += spec * 0.8F;
            int ri = Math.max(0, Math.min(255, (int) (r * 255.0F))), gi = Math.max(0, Math.min(255, (int) (g * 255.0F))), bi = Math.max(0, Math.min(255, (int) (b * 255.0F)));
            int ai = Math.max(0, Math.min(255, (int) (a * 255.0F)));
            consumer.addVertex(pose, x, y, z)
                    .setColor((ai << 24) | (ri << 16) | (gi << 8) | bi)
                    .setUv(0.5F, 0.5F)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightCoordsUtil.FULL_BRIGHT)
                    .setNormal(pose, nx, ny, nz);
        }
    }
}
