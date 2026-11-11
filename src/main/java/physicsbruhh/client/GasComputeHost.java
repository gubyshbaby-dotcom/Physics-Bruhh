package physicsbruhh.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import physicsbruhh.PhysicsBruhh;
import physicsbruhh.client.compute.VkCompute;

/**
 * The gas solver as compute shaders ({@link GasCompute}), in the game.
 *
 * <p>The game's graphics layer has no compute shaders. It is built on Vulkan or on OpenGL, though,
 * and lets what is under it be reached: on Vulkan the device, its allocator and a command buffer
 * that is run in order with the game's own; on OpenGL the calls themselves, on the thread that
 * draws. The solver's programs run there, in textures of their own, and after each step the part
 * of the result that was computed is copied into a texture of the game's ({@link #gas}), which the
 * passes that draw the gas read as they read the result of the other solvers.
 *
 * <p>None of this has run on a graphics card: only on the software Vulkan and OpenGL of the build
 * machines. That is why it is asked for ({@code /gaslook solver compute}) and not chosen.
 */
final class GasComputeHost {
    private static Boolean possible;

    /** What is under the game's graphics layer. */
    private static Object backend() throws ReflectiveOperationException {
        Object device = RenderSystem.getDevice();
        Field field = device.getClass().getDeclaredField("backend");
        field.setAccessible(true);
        return field.get(device);
    }

    /** Whether compute shaders can be tried on this machine (setting them up may still fail). */
    static boolean possible() {
        if (possible == null) {
            try {
                String kind = backend().getClass().getName();
                possible = kind.endsWith(".VulkanDevice") || kind.endsWith(".GlDevice") && GasComputeOpenGl.supported();
                if (!possible) PhysicsBruhh.LOGGER.info("Gas: no compute shaders here ({}{})", kind, kind.endsWith(".GlDevice") ? ", OpenGL " + GL45C.glGetString(GL45C.GL_VERSION) : "");
            } catch (Throwable t) {
                possible = false;
                PhysicsBruhh.LOGGER.info("Gas: what is under the game's graphics layer could not be reached for compute shaders ({})", t.toString());
            }
        }
        return possible;
    }

    /** The text of a shader file of the mod ("core/gas_spin.fsh", "include/gas.glsl"). */
    private static String shader(String name) {
        try (InputStream in = PhysicsBruhh.class.getResourceAsStream("/assets/" + PhysicsBruhh.MODID + "/shaders/" + name)) {
            if (in == null) throw new IllegalStateException("the shader " + name + " is not in the mod");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("the shader " + name + " could not be read", e);
        }
    }

    private final GasBox box;
    private final GasCompute gas;
    private final boolean vulkan;
    private final GpuTexture shown;
    private final GpuTextureView shownView;
    /** The part of the shown texture (in blocks of the box) that may hold something. */
    private final int[] shownHolds = new int[6];
    private final ByteBuffer grid;
    /** For each layer of the fine grid that was computed: where its rectangle is in the texture (x, y, width, height). */
    private final int[] rectangles;

    GasComputeHost(GasBox box) throws ReflectiveOperationException {
        this.box = box;
        Object under = backend();
        String kind = under.getClass().getName();
        if (kind.endsWith(".VulkanDevice")) {
            gas = OnVulkan.create(box, under);
            vulkan = true;
        } else if (kind.endsWith(".GlDevice")) {
            gas = new GasComputeOpenGl(box, GasComputeHost::shader);
            vulkan = false;
        } else {
            throw new IllegalStateException("a graphics layer this does not know (" + kind + ")");
        }
        shown = RenderSystem.getDevice().createTexture(() -> "Gas: computed", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA16_FLOAT, box.fineW, box.fineH, 1, 1);
        shownView = RenderSystem.getDevice().createTextureView(shown);
        // (not known to be empty: emptied before the first copy into it)
        shownHolds[3] = box.bx; shownHolds[4] = box.by; shownHolds[5] = box.bz;
        grid = MemoryUtil.memCalloc(GasBox.GRID_FLOATS * 4).order(ByteOrder.nativeOrder());
        rectangles = new int[box.fy * 4];
    }

    String on() {
        return vulkan ? "Vulkan" : "OpenGL";
    }

    int steps() {
        return gas.steps;
    }

    /** Smoke, heat and fuel as the last step left them. */
    GpuTextureView gas() {
        return shownView;
    }

    void forget() {
        gas.forget();
        shownHolds[0] = 0; shownHolds[1] = 0; shownHolds[2] = 0; shownHolds[3] = box.bx; shownHolds[4] = box.by; shownHolds[5] = box.bz;
    }

    /**
     * One step, and its result into the texture the drawing reads. {@code map}: the map of the world if it has changed
     * since the last step (four bytes a block, as the passes read it), or null. The rest as {@link GasBox#grid}.
     */
    void step(CommandEncoder encoder, float dt, float time, int movedX, int movedY, int movedZ, float[] blobs, int blobCount, int[] part, ByteBuffer map) {
        box.grid(grid.asFloatBuffer(), dt, time, movedX, movedY, movedZ, blobs, blobCount, part);
        final int cells = box.cpb * box.ratio;
        int count = 0;
        for (int layer = part[1] * cells; layer < part[4] * cells; layer++, count++) {
            rectangles[count * 4] = (layer % box.fineTiles) * box.fx + part[0] * cells;
            rectangles[count * 4 + 1] = (layer / box.fineTiles) * box.fz + part[2] * cells;
            rectangles[count * 4 + 2] = (part[3] - part[0]) * cells;
            rectangles[count * 4 + 3] = (part[5] - part[2]) * cells;
        }
        // the shown texture must hold nothing outside the part that is computed
        int[] h = shownHolds;
        boolean holds = h[3] > h[0] && h[4] > h[1] && h[5] > h[2];
        boolean empty = holds && (h[0] < part[0] || h[1] < part[1] || h[2] < part[2] || h[3] > part[3] || h[4] > part[4] || h[5] > part[5]);
        if (empty || !holds) {
            System.arraycopy(part, 0, h, 0, 6);
        } else {
            for (int i = 0; i < 3; i++) { h[i] = Math.min(h[i], part[i]); h[i + 3] = Math.max(h[i + 3], part[i + 3]); }
        }
        if (vulkan) OnVulkan.step((GasComputeVulkan) gas, encoder, grid, map, part, shown, empty, rectangles, count);
        else OnOpenGl.step((GasComputeOpenGl) gas, grid, map, part, shown, empty, rectangles, count);
    }

    void close() {
        try {
            if (vulkan) OnVulkan.close((GasComputeVulkan) gas);
            else gas.close();
        } finally {
            shownView.close();
            shown.close();
            MemoryUtil.memFree(grid);
        }
    }

    /** What only Vulkan needs (kept apart: nothing of it is touched while the game draws with OpenGL). */
    private static final class OnVulkan {
        static GasCompute create(GasBox box, Object under) {
            VulkanDevice device = (VulkanDevice) under;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // the game records into command buffers of its graphics queue: compute shaders have to be allowed there
                IntBuffer count = stack.mallocInt(1);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(), count, null);
                VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(), count, families);
                int family = device.graphicsQueue().queueFamilyIndex();
                if (family < 0 || family >= count.get(0) || (families.get(family).queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT) == 0) {
                    throw new IllegalStateException("the queue the game draws with does not run compute shaders");
                }
            }
            return new GasComputeVulkan(box, device.vkDevice(), device.vma(), GasComputeHost::shader);
        }

        static void step(GasComputeVulkan gas, CommandEncoder encoder, ByteBuffer grid, ByteBuffer map, int[] part, GpuTexture shown, boolean empty, int[] rectangles, int count) {
            VulkanCommandEncoder backend = (VulkanCommandEncoder) ((FrontendCommandEncoder) encoder).backend();
            long to = ((VulkanGpuTexture) shown).vkImage();
            // a command buffer of the frame's own, run after what the game has recorded so far and before what it records next
            VkCommandBuffer commands = backend.allocateAndBeginTransientCommandBuffer();
            gas.begin(commands);
            gas.step(grid, map, part);
            VkCompute compute = gas.compute();
            compute.all(commands);
            if (empty) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                    VK10.vkCmdClearColorImage(commands, to, VK10.VK_IMAGE_LAYOUT_GENERAL, VkClearColorValue.calloc(stack), range);
                }
                compute.all(commands);
            }
            compute.copyRectangles(commands, (VkCompute.Image) gas.gas(), to, rectangles, count);
            gas.end();
            int result = VK10.vkEndCommandBuffer(commands);
            if (result != VK10.VK_SUCCESS) throw new IllegalStateException("Vulkan: the gas solver's commands could not be finished (" + result + ")");
            backend.execute(commands);
        }

        static void close(GasComputeVulkan gas) {
            // the graphics card may still be working with it: freed when the frames now under way are done
            VulkanCommandEncoder backend = (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
            backend.queueForDestroy(gas::close);
        }
    }

    /** What only OpenGL needs. */
    private static final class OnOpenGl {
        static void step(GasComputeOpenGl gas, ByteBuffer grid, ByteBuffer map, int[] part, GpuTexture shown, boolean empty, int[] rectangles, int count) {
            int to = ((GlTexture) shown).glId();
            gas.begin();
            try {
                gas.step(grid, map, part);
                GL45C.glMemoryBarrier(GL45C.GL_ALL_BARRIER_BITS);
                if (empty) GL45C.glClearTexImage(to, 0, GL45C.GL_RGBA, GL45C.GL_FLOAT, (ByteBuffer) null);
                GasComputeOpenGl.copy(gas.gas(), to, rectangles, count);
            } finally {
                gas.end();
            }
        }
    }
}
