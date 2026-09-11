package ru.paulevs.bismuthlib.data;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.paulevs.bismuthlib.ColorMath;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Sparse GPU storage for section light data.
 * <p>
 * Texture layout: rows [0, slotBase) are a page table with one texel per section cell of the light map
 * (slot index in red and green channels, 0 = no light). Below it slots of 64x64 texels (16x16x16 blocks)
 * are allocated only for sections that actually have light, so memory depends on the number of lit
 * sections instead of the map volume. The texture grows on demand. Must be used on the render thread.
 */
public class LightAtlas {
	public static final int SLOT_SIDE = 64;
	private static final Logger LOGGER = LoggerFactory.getLogger("bismuthlib");
	private static final int SLOT_TEXELS = SLOT_SIDE * SLOT_SIDE;
	private static final int MAX_WIDTH = 4096;
	private static final int MAX_HEIGHT = 16384;
	private static final int INITIAL_SLOT_ROWS = 16;
	private static final int MAX_SLOT_ID = 0xFFFF;

	private final int width;
	private final int slotsPerRow;
	private final int pageRows;
	private final int slotBase;
	private final int maxSlotRows;
	private final int[] pageTable;
	private final IntBuffer pageBuffer;
	private final IntBuffer slotBuffer;
	private final IntArrayList freeSlots = new IntArrayList();
	private int minDirtyRow;
	private int maxDirtyRow;
	private int slotRows;
	private int nextSlot = 1;
	private int textureId;
	private boolean contentLost;

	public LightAtlas(int cellCount) {
		int maxSize = Math.min(GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE), MAX_HEIGHT);
		width = Math.min(MAX_WIDTH, Integer.highestOneBit(maxSize));
		slotsPerRow = width / SLOT_SIDE;
		pageRows = (cellCount + width - 1) / width;
		slotBase = (pageRows + SLOT_SIDE - 1) / SLOT_SIDE * SLOT_SIDE;

		int neededRows = (cellCount + slotsPerRow) / slotsPerRow;
		int textureRows = (maxSize - slotBase) / SLOT_SIDE;
		int idRows = (MAX_SLOT_ID + 1) / slotsPerRow;
		maxSlotRows = Math.max(1, Math.min(neededRows, Math.min(textureRows, idRows)));
		slotRows = Math.min(INITIAL_SLOT_ROWS, maxSlotRows);

		pageTable = new int[cellCount];
		pageBuffer = MemoryUtil.memAllocInt(pageRows * width);
		slotBuffer = MemoryUtil.memAllocInt(SLOT_TEXELS);
		textureId = createTexture(getHeight());

		minDirtyRow = 0;
		maxDirtyRow = pageRows - 1;
		flushPageTable();
	}

	public int getTextureId() {
		return textureId;
	}

	public int getSlotBase() {
		return slotBase;
	}

	/**
	 * Upper bound (exclusive) for slot indices.
	 */
	public int getMaxSlots() {
		return slotsPerRow * maxSlotRows;
	}

	/**
	 * @return slot index or 0 if the atlas is full and can't grow
	 */
	public int allocateSlot() {
		if (!freeSlots.isEmpty()) return freeSlots.popInt();
		if (nextSlot >= slotsPerRow * slotRows && !grow()) return 0;
		return nextSlot++;
	}

	public void freeSlot(int slot) {
		freeSlots.add(slot);
	}

	/**
	 * @return true once after the texture was reallocated without keeping old slot data
	 */
	public boolean consumeContentLost() {
		boolean result = contentLost;
		contentLost = false;
		return result;
	}

	public void setPage(int cell, int slot) {
		if (pageTable[cell] == slot) return;
		pageTable[cell] = slot;
		int row = cell / width;
		if (row < minDirtyRow) minDirtyRow = row;
		if (row > maxDirtyRow) maxDirtyRow = row;
	}

	public void uploadSlot(int slot, int[] data) {
		slotBuffer.clear();
		slotBuffer.put(data, 0, SLOT_TEXELS);
		slotBuffer.flip();
		bindForUpload();
		GL11.glTexSubImage2D(
			GL11.GL_TEXTURE_2D, 0,
			(slot % slotsPerRow) * SLOT_SIDE, slotBase + (slot / slotsPerRow) * SLOT_SIDE,
			SLOT_SIDE, SLOT_SIDE,
			GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, slotBuffer
		);
	}

	public void flushPageTable() {
		if (maxDirtyRow < minDirtyRow) return;

		int start = minDirtyRow * width;
		int end = (maxDirtyRow + 1) * width;
		pageBuffer.clear();
		for (int i = start; i < end; i++) {
			int slot = i < pageTable.length ? pageTable[i] : 0;
			pageBuffer.put(slot | ColorMath.ALPHA);
		}
		pageBuffer.flip();

		bindForUpload();
		GL11.glTexSubImage2D(
			GL11.GL_TEXTURE_2D, 0,
			0, minDirtyRow, width, maxDirtyRow - minDirtyRow + 1,
			GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pageBuffer
		);

		minDirtyRow = Integer.MAX_VALUE;
		maxDirtyRow = -1;
	}

	public void close() {
		TextureUtil.releaseTextureId(textureId);
		MemoryUtil.memFree(pageBuffer);
		MemoryUtil.memFree(slotBuffer);
	}

	private int getHeight() {
		return slotBase + slotRows * SLOT_SIDE;
	}

	private boolean grow() {
		if (slotRows >= maxSlotRows) return false;

		int oldId = textureId;
		int oldHeight = getHeight();
		slotRows = Math.min(slotRows * 2, maxSlotRows);
		int newId = createTexture(getHeight());

		if (!copyTexture(oldId, newId, oldHeight)) {
			LOGGER.warn("Failed to copy colored light atlas, light will be recalculated");
			contentLost = true;
			minDirtyRow = 0;
			maxDirtyRow = pageRows - 1;
		}

		TextureUtil.releaseTextureId(oldId);
		textureId = newId;
		flushPageTable();
		return true;
	}

	private void bindForUpload() {
		GlStateManager._bindTexture(textureId);
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
		GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
	}

	private int createTexture(int height) {
		int id = TextureUtil.generateTextureId();
		GlStateManager._bindTexture(id);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (IntBuffer) null);
		return id;
	}

	private boolean copyTexture(int source, int target, int height) {
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
			GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, source, 0);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBuffer);
			GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);

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
}
