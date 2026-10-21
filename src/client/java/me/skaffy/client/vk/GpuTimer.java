package me.skaffy.client.vk;

import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/**
 * GPU timestamps around the mod's passes, read back {@link Vk#FRAME_RING} frames later and smoothed, so /water hf stats can show
 * where the GPU time goes. Each {@link #mark} closes the section since the previous mark.
 */
public final class GpuTimer implements AutoCloseable {
	private static final int MAX = 24;

	private final long pool;
	private final double nsPerTick;
	private final String[][] labels = new String[Vk.FRAME_RING][MAX];
	private final int[] count = new int[Vk.FRAME_RING];
	private final Map<String, Double> smoothed = new LinkedHashMap<>();
	private double total;
	private int slot;

	public GpuTimer() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceProperties(Vk.vk().getPhysicalDevice(), props);
			this.nsPerTick = props.limits().timestampPeriod();
			VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
				.queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(MAX * Vk.FRAME_RING);
			LongBuffer p = stack.mallocLong(1);
			Vk.check(VK10.vkCreateQueryPool(Vk.vk(), info, null, p), "vkCreateQueryPool");
			this.pool = p.get(0);
		}
	}

	/** Call once per frame on the frame's command buffer, before any {@link #mark}. Reads this ring slot's old results first. */
	public void begin(VkCommandBuffer cb) {
		this.slot = Vk.ringSlot();
		this.read();
		VK10.vkCmdResetQueryPool(cb, this.pool, this.slot * MAX, MAX);
		this.count[this.slot] = 0;
		this.mark(cb, null);
	}

	/** Ends the section named {@code label} (time since the previous mark). */
	public void mark(VkCommandBuffer cb, String label) {
		int n = this.count[this.slot];
		if (n >= MAX) {
			return;
		}
		VK10.vkCmdWriteTimestamp(cb, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, this.pool, this.slot * MAX + n);
		this.labels[this.slot][n] = label;
		this.count[this.slot] = n + 1;
	}

	private void read() {
		int n = this.count[this.slot];
		if (n < 2) {
			return;
		}
		this.count[this.slot] = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer ticks = stack.mallocLong(n);
			int r = VK10.vkGetQueryPoolResults(Vk.vk(), this.pool, this.slot * MAX, n, ticks, 8L, VK10.VK_QUERY_RESULT_64_BIT);
			if (r != VK10.VK_SUCCESS) {
				return;
			}
			Map<String, Double> frame = new LinkedHashMap<>();
			for (int i = 1; i < n; i++) {
				double ms = (ticks.get(i) - ticks.get(i - 1)) * this.nsPerTick / 1.0e6;
				frame.merge(this.labels[this.slot][i], Math.max(ms, 0.0), Double::sum);
			}
			double sum = 0.0;
			for (Map.Entry<String, Double> e : frame.entrySet()) {
				this.smoothed.merge(e.getKey(), e.getValue(), (old, now) -> old * 0.9 + now * 0.1);
				sum += e.getValue();
			}
			// Sections that did not run this frame decay toward zero.
			for (Map.Entry<String, Double> e : this.smoothed.entrySet()) {
				if (!frame.containsKey(e.getKey())) {
					e.setValue(e.getValue() * 0.9);
				}
			}
			this.total = this.total * 0.9 + sum * 0.1;
		}
	}

	/** "label 1.23 ms, ..." of the smoothed sections, largest first, plus the total. */
	public String summary() {
		StringBuilder b = new StringBuilder(String.format("GPU %.2f ms:", this.total));
		this.smoothed.entrySet().stream()
			.sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
			.filter(e -> e.getValue() >= 0.01)
			.forEach(e -> b.append(String.format(" %s %.2f,", e.getKey(), e.getValue())));
		b.setLength(b.length() - (b.charAt(b.length() - 1) == ',' ? 1 : 0));
		return b.toString();
	}

	@Override
	public void close() {
		VK10.vkDestroyQueryPool(Vk.vk(), this.pool, null);
	}
}
