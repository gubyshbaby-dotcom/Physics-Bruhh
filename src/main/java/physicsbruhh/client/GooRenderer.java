package physicsbruhh.client;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.NeoForgeRenderTypes;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import physicsbruhh.sim.GooSim;

/**
 * Draws the goo surface. The mesh comes from the simulation thread as quads (position + normal);
 * here it gets the vanilla honey / slime block texture (projected along the dominant axis of each
 * face), simple directional shading and the light level of the block it is in.
 */
public final class GooRenderer {
    private static final Identifier[] TEXTURES = {
            Identifier.withDefaultNamespace("textures/block/honey_block_side.png"),
            Identifier.withDefaultNamespace("textures/block/slime_block.png")
    };
    private static final int[] ALPHA = { 0xD8, 0xC8 };
    private static final float[][] local = { new float[0], new float[0] };
    private static final int[] quads = { 0, 0 };
    private static int seenVersion = -1;
    /** Draw callbacks that actually ran, and quads emitted by them (smoke test and debugging). */
    static volatile int drawCalls, drawnQuads;

    private GooRenderer() {}

    static void submit(SubmitCustomGeometryEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
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
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        stack.translate(origin.getX() - camera.x, origin.getY() - camera.y, origin.getZ() - camera.z);
        for (int mat = 0; mat < 2; mat++) {
            final int count = quads[mat];
            if (count == 0) continue;
            final float[] data = local[mat];
            final int alpha = ALPHA[mat];
            RenderType type = NeoForgeRenderTypes.getEntityUnlitTranslucent(TEXTURES[mat]);
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
                    float fx = (float) Math.floor(data[o]), fy = (float) Math.floor(data[o + 1]), fz = (float) Math.floor(data[o + 2]);
                    for (int v = 0; v < 4; v++) {
                        int i = o + v * 6;
                        float x = data[i], y = data[i + 1], z = data[i + 2], nx = data[i + 3], ny = data[i + 4], nz = data[i + 5];
                        // top faces brightest, sides dimmer along one axis, like vanilla block shading
                        float shade = (0.62F + 0.30F * Math.max(0.0F, ny) + 0.10F * Math.abs(nx) - 0.06F * Math.abs(nz)) * light;
                        int c = Math.max(0, Math.min(255, (int) (shade * 255.0F)));
                        float ax = Math.abs(data[o + 3]), ay = Math.abs(data[o + 4]), az = Math.abs(data[o + 5]);
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
            });
        }
        stack.popPose();
    }
}
