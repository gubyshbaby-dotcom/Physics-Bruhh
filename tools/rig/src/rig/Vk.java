package rig;

import static org.lwjgl.vulkan.VK10.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

/** A Vulkan device without the game: the software one, a queue, an allocator, a command buffer that is run and waited for. */
public final class Vk implements AutoCloseable {
    public final VkInstance instance;
    public final VkPhysicalDevice physical;
    public final VkDevice device;
    public final VkQueue queue;
    public final long vma, pool;
    public final String name;

    public Vk() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK12.VK_API_VERSION_1_2);
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            String layers = System.getenv("VK_LAYERS");
            if (layers != null && !layers.isEmpty()) info.ppEnabledLayerNames(stack.pointers(stack.UTF8(layers)));
            PointerBuffer p = stack.mallocPointer(1);
            check(vkCreateInstance(info, null, p));
            instance = new VkInstance(p.get(0), info);
            IntBuffer count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null));
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices));
            physical = new VkPhysicalDevice(devices.get(0), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, props);
            name = props.deviceNameString();
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            int family = -1;
            for (int i = 0; i < count.get(0); i++) if ((families.get(i).queueFlags() & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) { family = i; break; }
            if (family < 0) throw new IllegalStateException("no queue for graphics and compute");
            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1.0F));
            // (as the game: none of the optional features of storage images are turned on)
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues);
            check(vkCreateDevice(physical, deviceInfo, null, p));
            device = new VkDevice(p.get(0), physical, deviceInfo);
            vkGetDeviceQueue(device, family, 0, p);
            queue = new VkQueue(p.get(0), device);
            VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(instance, device);
            VmaAllocatorCreateInfo allocator = VmaAllocatorCreateInfo.calloc(stack).instance(instance).physicalDevice(physical).device(device).pVulkanFunctions(functions).vulkanApiVersion(VK12.VK_API_VERSION_1_2);
            check(Vma.vmaCreateAllocator(allocator, p));
            vma = p.get(0);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, out));
            pool = out.get(0);
        }
    }

    static void check(int result) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan error " + result);
    }

    public VkCommandBuffer begin() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), p));
            VkCommandBuffer commands = new VkCommandBuffer(p.get(0), device);
            check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)));
            return commands;
        }
    }

    /** Runs the commands and waits until they are done. Returns the seconds that took. */
    public double run(VkCommandBuffer commands) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkEndCommandBuffer(commands));
            long start = System.nanoTime();
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commands)), VK_NULL_HANDLE));
            check(vkQueueWaitIdle(queue));
            double took = (System.nanoTime() - start) / 1.0e9;
            vkFreeCommandBuffers(device, pool, commands);
            return took;
        }
    }

    @Override
    public void close() {
        vkDestroyCommandPool(device, pool, null);
        Vma.vmaDestroyAllocator(vma);
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
    }
}
