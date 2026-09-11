package ru.paulevs.bismuthlib.data;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.paulevs.bismuthlib.ColorMath;
import ru.paulevs.bismuthlib.compat.ColoredLightTexture;

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
	private ColoredLightTexture texture;
	private int minDirtyRow;
	private int maxDirtyRow;
	private int slotRows;
	private int nextSlot = 1;
	private boolean contentLost;

	public LightAtlas(int cellCount) {
		int maxSize = Math.min(ColoredLightTexture.getMaxSize(), MAX_HEIGHT);
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
		texture = new ColoredLightTexture(width, getHeight());

		minDirtyRow = 0;
		maxDirtyRow = pageRows - 1;
		flushPageTable();
	}

	/**
	 * Texture that shaders sample. It is replaced when the atlas grows, so don't keep a reference.
	 */
	public ColoredLightTexture getTexture() {
		return texture;
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
		texture.upload(
			(slot % slotsPerRow) * SLOT_SIDE, slotBase + (slot / slotsPerRow) * SLOT_SIDE,
			SLOT_SIDE, SLOT_SIDE, slotBuffer
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

		texture.upload(0, minDirtyRow, width, maxDirtyRow - minDirtyRow + 1, pageBuffer);

		minDirtyRow = Integer.MAX_VALUE;
		maxDirtyRow = -1;
	}

	public void close() {
		texture.close();
		MemoryUtil.memFree(pageBuffer);
		MemoryUtil.memFree(slotBuffer);
	}

	private int getHeight() {
		return slotBase + slotRows * SLOT_SIDE;
	}

	private boolean grow() {
		if (slotRows >= maxSlotRows) return false;

		ColoredLightTexture old = texture;
		int oldHeight = getHeight();
		slotRows = Math.min(slotRows * 2, maxSlotRows);
		ColoredLightTexture grown = new ColoredLightTexture(width, getHeight());

		boolean copied;
		try {
			copied = grown.copyFrom(old, width, oldHeight);
		}
		catch (RuntimeException e) {
			LOGGER.warn("Failed to copy colored light atlas", e);
			copied = false;
		}

		if (!copied) {
			LOGGER.warn("Colored light atlas was not copied, light will be recalculated");
			contentLost = true;
			minDirtyRow = 0;
			maxDirtyRow = pageRows - 1;
		}

		old.close();
		texture = grown;
		flushPageTable();
		return true;
	}
}
