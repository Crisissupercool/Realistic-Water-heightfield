package me.skaffy.client.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

public record FrameTarget(long colorImage, long colorView, long depthImage, long depthView, int width, int height, int colorFormat, boolean layer) {
	public static @Nullable FrameTarget main() {
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		return of(main.getColorTextureView(), main.getDepthTextureView(), false);
	}

	public static @Nullable FrameTarget of(@Nullable GpuTextureView color, @Nullable GpuTextureView depth, boolean layer) {
		if (!(color instanceof VulkanGpuTextureView colorView) || !(depth instanceof VulkanGpuTextureView depthView)
			|| !(colorView.texture() instanceof VulkanGpuTexture colorTex) || !(depthView.texture() instanceof VulkanGpuTexture depthTex)) {
			return null;
		}
		int width = colorTex.getWidth(0);
		int height = colorTex.getHeight(0);
		if (depthTex.getWidth(0) != width || depthTex.getHeight(0) != height) {
			return null;
		}
		return new FrameTarget(colorTex.vkImage(), colorView.vkImageView(), depthTex.vkImage(), depthView.vkImageView(), width, height,
			VulkanConst.toVk(colorTex.getFormat()), layer);
	}
}
