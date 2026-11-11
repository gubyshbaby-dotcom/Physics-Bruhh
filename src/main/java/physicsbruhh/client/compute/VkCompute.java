package physicsbruhh.client.compute;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Compute shaders on Vulkan, by Vulkan's own calls: the game's graphics layer has render passes
 * only, but it is built on a Vulkan device that can do more, and hands that device out.
 *
 * <p>Nothing here knows about the game: a device, its allocator and command buffers to record
 * into are all it takes, so the same code runs in a test rig without the game.
 *
 * <p>Kept simple the way the game's own Vulkan layer is: every image stays in the general layout,
 * and between two dispatches that touch the same memory there is one barrier for all memory.
 * What a program reads and writes is fixed when a {@link Bound} is made; only the place of the
 * uniforms in their buffer changes from one use to the next.
 */
public final class VkCompute implements AutoCloseable {
    public static final int RGBA16F = VK_FORMAT_R16G16B16A16_SFLOAT, R32F = VK_FORMAT_R32_SFLOAT, RGBA8 = VK_FORMAT_R8G8B8A8_UNORM;

    private final VkDevice device;
    private final long vma;
    private long pool;
    private final List<Runnable> cleanup = new ArrayList<>();

    public VkCompute(VkDevice device, long vma) {
        this.device = device;
        this.vma = vma;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(256);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2048);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1024);
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(256).pPoolSizes(sizes);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateDescriptorPool(device, info, null, out), "descriptor pool");
            pool = out.get(0);
        }
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan: " + what + " failed (" + result + ")");
    }

    // ------------------------------------------------------------------ shaders

    /** A compute shader in GLSL, compiled for Vulkan. The bytes are freed by the caller ({@link MemoryUtil#memFree}). */
    public static ByteBuffer compile(String name, String source) {
        long compiler = Shaderc.shaderc_compiler_initialize(), options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0L || options == 0L) throw new IllegalStateException("the shader compiler could not be started");
        ByteBuffer text = MemoryUtil.memUTF8(source, false), file = MemoryUtil.memUTF8(name), entry = MemoryUtil.memUTF8("main");
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_1);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            result = Shaderc.shaderc_compile_into_spv(compiler, text, Shaderc.shaderc_compute_shader, file, entry, options);
            if (result == 0L) throw new IllegalStateException("the shader compiler gave nothing back for " + name);
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new IllegalStateException("compute shader " + name + ": " + Shaderc.shaderc_result_get_error_message(result));
            }
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
            MemoryUtil.memCopy(bytes, copy);
            return copy;
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
            MemoryUtil.memFree(text); MemoryUtil.memFree(file); MemoryUtil.memFree(entry);
        }
    }

    /** What a program has at one binding. */
    public enum Kind { UNIFORMS, SAMPLED, STORED }

    /** A compute program: what kind of thing it has at each binding, in order from binding 0. */
    public final class Program {
        final long module, setLayout, layout, pipeline;
        final Kind[] kinds;

        Program(ByteBuffer spirv, Kind[] kinds) {
            this.kinds = kinds;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, out), "shader module");
                module = out.get(0);
                VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(kinds.length, stack);
                for (int i = 0; i < kinds.length; i++) {
                    bindings.get(i).binding(i).descriptorType(type(kinds[i])).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                }
                check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, out), "descriptor set layout");
                setLayout = out.get(0);
                check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)), null, out), "pipeline layout");
                layout = out.get(0);
                VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
                info.get(0).sType$Default().layout(layout);
                info.get(0).stage(VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main")));
                check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, out), "compute pipeline");
                pipeline = out.get(0);
            }
            cleanup.add(() -> {
                vkDestroyPipeline(device, pipeline, null);
                vkDestroyPipelineLayout(device, layout, null);
                vkDestroyDescriptorSetLayout(device, setLayout, null);
                vkDestroyShaderModule(device, module, null);
            });
        }

        /**
         * The program with the things it reads and writes: for each binding in order a {@link Uniforms}, a
         * {@link Sampled} or an {@link Image} (written to).
         */
        public Bound bind(Object... things) {
            if (things.length != kinds.length) throw new IllegalArgumentException("a program with " + kinds.length + " bindings was given " + things.length + " things");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer out = stack.mallocLong(1);
                check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), out), "descriptor set");
                long set = out.get(0);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(kinds.length, stack);
                for (int i = 0; i < kinds.length; i++) {
                    VkWriteDescriptorSet write = writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorType(type(kinds[i])).descriptorCount(1);
                    switch (kinds[i]) {
                        case UNIFORMS -> {
                            Uniforms uniforms = (Uniforms) things[i];
                            write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(uniforms.buffer).offset(0L).range(uniforms.size));
                        }
                        case SAMPLED -> {
                            Sampled sampled = (Sampled) things[i];
                            write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampled.sampler).imageView(sampled.image.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                        }
                        case STORED -> {
                            Image image = (Image) things[i];
                            write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(image.view).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                        }
                    }
                }
                vkUpdateDescriptorSets(device, writes, null);
                return new Bound(this, set);
            }
        }
    }

    /** A program together with what it reads and writes. */
    public static final class Bound {
        final Program program;
        final long set;

        Bound(Program program, long set) {
            this.program = program;
            this.set = set;
        }
    }

    private static int type(Kind kind) {
        return switch (kind) {
            case UNIFORMS -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
            case SAMPLED -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case STORED -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        };
    }

    public Program program(ByteBuffer spirv, Kind... kinds) {
        return new Program(spirv, kinds);
    }

    /** Runs a program over {@code x * y * z} groups of cells, with the uniforms of the given slot. */
    public void dispatch(VkCommandBuffer commands, Bound bound, int uniformsAt, int x, int y, int z) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, bound.program.pipeline);
            boolean uniforms = bound.program.kinds.length > 0 && bound.program.kinds[0] == Kind.UNIFORMS;
            IntBuffer offsets = uniforms ? stack.ints(uniformsAt) : null;
            vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, bound.program.layout, 0, stack.longs(bound.set), offsets);
            vkCmdDispatch(commands, x, y, z);
        }
    }

    /** What compute shaders have written so far can be read by the ones that follow. */
    public void between(VkCommandBuffer commands) {
        barrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    /** What compute shaders have written can be read by anything that follows (copies, the game's render passes). */
    public void after(VkCommandBuffer commands) {
        barrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    }

    /** What anything before has written (uploads, the game's passes) can be read by compute shaders. */
    public void before(VkCommandBuffer commands) {
        barrier(commands, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private static void barrier(VkCommandBuffer commands, int from, int to) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(commands, from, to, 0, barrier, null, null);
        }
    }

    // ------------------------------------------------------------------ uniforms

    /**
     * Numbers handed to the programs: a buffer of several slots, so that the numbers of a step still being worked on by
     * the graphics card are not overwritten by those of the next.
     */
    public final class Uniforms {
        final long buffer, allocation;
        final int size, stride, slots;
        private final ByteBuffer mapped;
        private int slot;

        Uniforms(int size, int slots) {
            this.size = size;
            this.slots = slots;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VkPhysicalDeviceProperties properties = org.lwjgl.vulkan.VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), properties);
                int align = (int) Math.max(16L, properties.limits().minUniformBufferOffsetAlignment());
                stride = (size + align - 1) / align * align;
                VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size((long) stride * slots).usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo how = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
                LongBuffer out = stack.mallocLong(1);
                PointerBuffer allocated = stack.mallocPointer(1);
                VmaAllocationInfo where = VmaAllocationInfo.calloc(stack);
                check(Vma.vmaCreateBuffer(vma, info, how, out, allocated, where), "uniform buffer");
                buffer = out.get(0);
                allocation = allocated.get(0);
                mapped = MemoryUtil.memByteBuffer(where.pMappedData(), stride * slots).order(java.nio.ByteOrder.nativeOrder());
            }
            cleanup.add(() -> Vma.vmaDestroyBuffer(vma, buffer, allocation));
        }

        /** The next slot, filled from {@code data} (position to limit). Returns where it is in the buffer, for {@link #dispatch}. */
        public int write(ByteBuffer data) {
            slot = (slot + 1) % slots;
            int at = slot * stride;
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(mapped) + at, Math.min(data.remaining(), size));
            Vma.vmaFlushAllocation(vma, allocation, at, stride);
            return at;
        }
    }

    public Uniforms uniforms(int size, int slots) {
        return new Uniforms(size, slots);
    }

    // ------------------------------------------------------------------ images

    /** A grid on the graphics card: {@code depth} of 1 makes it flat. */
    public final class Image {
        public final long image, allocation, view;
        public final int format, width, height, depth;
        private boolean fresh = true;

        Image(int format, int width, int height, int depth) {
            this.format = format; this.width = width; this.height = height; this.depth = depth;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
                        .imageType(depth > 1 ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                info.extent().set(width, height, depth);
                VmaAllocationCreateInfo how = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
                LongBuffer out = stack.mallocLong(1);
                PointerBuffer allocated = stack.mallocPointer(1);
                check(Vma.vmaCreateImage(vma, info, how, out, allocated, null), "image");
                image = out.get(0);
                allocation = allocated.get(0);
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                        .viewType(depth > 1 ? VK_IMAGE_VIEW_TYPE_3D : VK_IMAGE_VIEW_TYPE_2D).format(format);
                viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                check(vkCreateImageView(device, viewInfo, null, out), "image view");
                view = out.get(0);
            }
            cleanup.add(() -> {
                vkDestroyImageView(device, view, null);
                Vma.vmaDestroyImage(vma, image, allocation);
            });
        }

        /** Before its first use, and to empty it: into the layout everything here expects, and all zero. */
        public void clear(VkCommandBuffer commands) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (fresh) {
                    VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
                    barrier.get(0).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(0)
                            .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image);
                    barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
                    fresh = false;
                }
                org.lwjgl.vulkan.VkImageSubresourceRange range = org.lwjgl.vulkan.VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                vkCmdClearColorImage(commands, image, VK_IMAGE_LAYOUT_GENERAL, org.lwjgl.vulkan.VkClearColorValue.calloc(stack), range);
            }
        }
    }

    public Image image(int format, int width, int height, int depth) {
        return new Image(format, width, height, depth);
    }

    /** An image as a program reads it between cells: smoothly ({@code linear}) or the nearest cell, held at the edges. */
    public record Sampled(Image image, long sampler) {}

    private long linearSampler, nearestSampler;

    public Sampled sampled(Image image, boolean linear) {
        if ((linear ? linearSampler : nearestSampler) == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
                VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(filter).minFilter(filter).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0.0F);
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateSampler(device, info, null, out), "sampler");
                final long sampler = out.get(0);
                if (linear) linearSampler = sampler; else nearestSampler = sampler;
                cleanup.add(() -> vkDestroySampler(device, sampler, null));
            }
        }
        return new Sampled(image, linear ? linearSampler : nearestSampler);
    }

    /**
     * Layers of a grid (along its depth) copied into a flat image as tiles side by side: layer {@code from + i} goes to
     * the tile ({@code (from + i) % tiles}, {@code (from + i) / tiles}); of each layer only the rectangle
     * {@code x0, y0 .. x1, y1}. {@code to} is a Vulkan image in the general layout of the same format (the game's own).
     */
    public void copyLayers(VkCommandBuffer commands, Image grid, long to, int tiles, int from, int count, int x0, int y0, int x1, int y1) {
        if (count <= 0 || x1 <= x0 || y1 <= y0) return;
        VkImageCopy.Buffer regions = VkImageCopy.calloc(count);
        try {
            for (int i = 0; i < count; i++) {
                int layer = from + i;
                VkImageCopy region = regions.get(i);
                region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.srcOffset().set(x0, y0, layer);
                region.dstOffset().set((layer % tiles) * grid.width + x0, (layer / tiles) * grid.height + y0, 0);
                region.extent().set(x1 - x0, y1 - y0, 1);
            }
            vkCmdCopyImage(commands, grid.image, VK_IMAGE_LAYOUT_GENERAL, to, VK_IMAGE_LAYOUT_GENERAL, regions);
        } finally {
            regions.free();
        }
    }

    /** A whole image into a buffer of the processor's (for reading back). */
    public void copyOut(VkCommandBuffer commands, Image image, long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).imageExtent().set(image.width, image.height, image.depth);
            vkCmdCopyImageToBuffer(commands, image.image, VK_IMAGE_LAYOUT_GENERAL, buffer, region);
        }
    }

    /** A buffer into a whole image (for handing the solver the map of the world). */
    public void copyIn(VkCommandBuffer commands, long buffer, Image image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).imageExtent().set(image.width, image.height, image.depth);
            vkCmdCopyBufferToImage(commands, buffer, image.image, VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }

    /** A buffer the processor writes and reads, for {@link #copyIn} and {@link #copyOut}. */
    public final class Staging {
        public final long buffer, allocation;
        public final ByteBuffer bytes;

        Staging(int size) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo how = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
                LongBuffer out = stack.mallocLong(1);
                PointerBuffer allocated = stack.mallocPointer(1);
                VmaAllocationInfo where = VmaAllocationInfo.calloc(stack);
                check(Vma.vmaCreateBuffer(vma, info, how, out, allocated, where), "staging buffer");
                buffer = out.get(0);
                allocation = allocated.get(0);
                bytes = MemoryUtil.memByteBuffer(where.pMappedData(), size).order(java.nio.ByteOrder.nativeOrder());
            }
            cleanup.add(() -> Vma.vmaDestroyBuffer(vma, buffer, allocation));
        }

        public void flush() {
            Vma.vmaFlushAllocation(vma, allocation, 0, VK_WHOLE_SIZE);
        }

        public void fetch() {
            Vma.vmaInvalidateAllocation(vma, allocation, 0, VK_WHOLE_SIZE);
        }
    }

    public Staging staging(int size) {
        return new Staging(size);
    }

    /** Frees everything made here. The graphics card must have finished with it (the caller waits, or queues this). */
    @Override
    public void close() {
        for (int i = cleanup.size() - 1; i >= 0; i--) cleanup.get(i).run();
        cleanup.clear();
        if (pool != 0L) { vkDestroyDescriptorPool(device, pool, null); pool = 0L; }
    }
}
