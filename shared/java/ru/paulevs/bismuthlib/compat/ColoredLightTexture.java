package ru.paulevs.bismuthlib.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * RGBA GPU texture that stores colored light data (see LightAtlas). 1.20 - 1.21.4 use OpenGL directly,
 * newer 1.21.x source layers replace this class with GpuDevice based versions. Render thread only.
 */
public class ColoredLightTexture {
	private final int id;

	public static int getMaxSize() {
		return GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
	}

	public ColoredLightTexture(int width, int height) {
		id = TextureUtil.generateTextureId();
		GlStateManager._bindTexture(id);
		// Shaders read exact texels with texelFetch
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
	}

	/**
	 * Uploads pixels to a region. Colors are ABGR ints in native byte order (RGBA bytes).
	 */
	public void upload(int x, int y, int width, int height, IntBuffer pixels) {
		GlStateManager._bindTexture(id);
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
		GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
	}

	/**
	 * Copies a region starting at the origin from another texture to the origin of this one.
	 * @return false if the copy could not be done
	 */
	public boolean copyFrom(ColoredLightTexture source, int width, int height) {
		int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

		int readBuffer = GL30.glGenFramebuffers();
		int drawBuffer = GL30.glGenFramebuffers();
		boolean result = false;

		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer colorMask = stack.malloc(4);
			GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);

			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBuffer);
			GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, source.id, 0);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBuffer);
			GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, id, 0);

			if (
				GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE &&
				GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE
			) {
				if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
				GL11.glColorMask(true, true, true, true);
				GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
				GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
				if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
				result = true;
			}
		}
		finally {
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
			GL30.glDeleteFramebuffers(readBuffer);
			GL30.glDeleteFramebuffers(drawBuffer);
		}

		return result;
	}

	public int getId() {
		return id;
	}

	public void close() {
		TextureUtil.releaseTextureId(id);
	}
}
