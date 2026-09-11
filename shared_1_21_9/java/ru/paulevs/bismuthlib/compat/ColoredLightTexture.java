package ru.paulevs.bismuthlib.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * RGBA GPU texture that stores colored light data (see LightAtlas). 1.21.9 - 1.21.10: texture writes take byte buffers.
 * Render thread only, outside of render passes.
 */
public class ColoredLightTexture {
	private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;

	private final GpuTexture texture;
	private final GpuTextureView view;

	public static int getMaxSize() {
		return RenderSystem.getDevice().getMaxTextureSize();
	}

	public ColoredLightTexture(int width, int height) {
		texture = RenderSystem.getDevice().createTexture("bismuthlib_colored_light", USAGE, TextureFormat.RGBA8, width, height, 1, 1);
		// Shaders read exact texels with texelFetch
		texture.setTextureFilter(FilterMode.NEAREST, false);
		view = RenderSystem.getDevice().createTextureView(texture);
	}

	/**
	 * Uploads pixels to a region. Colors are ABGR ints in native byte order (RGBA bytes).
	 */
	public void upload(int x, int y, int width, int height, IntBuffer pixels) {
		ByteBuffer bytes = MemoryUtil.memByteBuffer(MemoryUtil.memAddress(pixels), pixels.remaining() * Integer.BYTES);
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, bytes, NativeImage.Format.RGBA, 0, 0, x, y, width, height);
	}

	/**
	 * Copies a region starting at the origin from another texture to the origin of this one.
	 * @return false if the copy could not be done
	 */
	public boolean copyFrom(ColoredLightTexture source, int width, int height) {
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source.texture, texture, 0, 0, 0, 0, 0, width, height);
		return true;
	}

	public GpuTextureView getTextureView() {
		return view;
	}

	public void close() {
		view.close();
		texture.close();
	}
}
